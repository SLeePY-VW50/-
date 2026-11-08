package com.campus.lab.checkin;

import com.campus.lab.checkin.dto.TerminalCheckinView;
import com.campus.lab.checkin.dto.TerminalReservationOption;
import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.dto.ReservationView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TerminalCheckinService {

    private static final String CHOICE_KEY_PREFIX = "checkin:terminal:choice:";
    private static final DefaultRedisScript<String> CONSUME_CHOICE_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('get', KEYS[1])
            if value then
                redis.call('del', KEYS[1])
            end
            return value
            """,
            String.class);

    private final TerminalAccountService accountService;
    private final ReservationCheckinTransactionService transactionService;
    private final ReservationRepository repository;
    private final LockManager lockManager;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CheckinRuleService ruleService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TerminalCheckinService(
            TerminalAccountService accountService,
            ReservationCheckinTransactionService transactionService,
            ReservationRepository repository,
            LockManager lockManager,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CheckinRuleService ruleService,
            Clock clock) {
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.repository = repository;
        this.lockManager = lockManager;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ruleService = ruleService;
        this.clock = clock;
    }

    public TerminalCheckinView checkin(
            TerminalSessionService.TerminalContext terminal, String account, String password) {
        long userId = accountService.authenticate(terminal.deviceId(), account, password);
        LocalDateTime now = LocalDateTime.now(clock);
        CheckinRuleService.CheckinRules rules = ruleService.rules();
        List<CheckinCandidate> candidates = jdbcTemplate.query("""
                SELECT resv_id, start_time FROM t_reservation
                WHERE user_id = ? AND lab_id = ? AND status = 'APPROVED'
                ORDER BY start_time, resv_id
                """, (rs, rowNum) -> new CheckinCandidate(
                        rs.getLong("resv_id"), rs.getObject("start_time", LocalDateTime.class)),
                userId, terminal.labId()).stream()
                .filter(candidate -> candidate.startTime() != null
                        && !now.isBefore(candidate.startTime().minusMinutes(rules.beforeMinutes()))
                        && !now.isAfter(candidate.startTime().plusMinutes(rules.graceMinutes())))
                .toList();
        if (candidates.isEmpty()) {
            List<Long> current = jdbcTemplate.queryForList("""
                    SELECT resv_id FROM t_reservation
                    WHERE user_id = ? AND lab_id = ? AND status = 'IN_USE'
                      AND checkin_time IS NOT NULL AND end_time > ?
                    ORDER BY checkin_time DESC, resv_id DESC LIMIT 1
                    """, Long.class, userId, terminal.labId(), now);
            if (current.isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "当前没有可签到预约");
            }
            return checkedIn(current.getFirst(), true);
        }
        if (candidates.size() == 1) {
            return checkinSelected(userId, terminal.labId(), candidates.getFirst().reservationId());
        }

        String choiceToken = newToken();
        List<Long> reservationIds = candidates.stream().map(CheckinCandidate::reservationId).toList();
        try {
            redisTemplate.opsForValue().set(
                    CHOICE_KEY_PREFIX + choiceToken,
                    objectMapper.writeValueAsString(
                            new StoredChoice(userId, terminal.labId(), reservationIds)),
                    Duration.ofSeconds(rules.choiceExpireSeconds()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("签到选择凭证无法序列化", exception);
        }
        List<TerminalReservationOption> options = candidates.stream()
                .map(candidate -> repository.findView(candidate.reservationId()))
                .filter(java.util.Objects::nonNull)
                .map(TerminalReservationOption::from)
                .toList();
        return TerminalCheckinView.choice(choiceToken, rules.choiceExpireSeconds(), options);
    }

    public TerminalCheckinView select(
            TerminalSessionService.TerminalContext terminal, String choiceToken, Long reservationId) {
        StoredChoice choice = consumeChoice(choiceToken);
        if (choice.labId() != terminal.labId()) {
            throw new BusinessException(HttpStatus.CONFLICT, "选择凭证与终端实验室不匹配");
        }
        if (reservationId == null || !choice.reservationIds().contains(reservationId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约不属于该选择凭证");
        }
        return checkinSelected(choice.userId(), terminal.labId(), reservationId);
    }

    private TerminalCheckinView checkinSelected(long userId, long labId, long reservationId) {
        ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
        if (target == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        if (target.labId() == null || target.labId() != labId) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约与终端实验室不匹配");
        }
        LabLockKey key = new LabLockKey(labId, target.date());
        List<ReservationLock> locks = lockManager.acquireAll(List.of(key));
        ReservationCheckinTransactionService.CheckinOutcome outcome;
        try {
            outcome = transactionService.checkin(
                    reservationId, userId, labId, key, CheckinChannel.TERMINAL_ACCOUNT);
        } finally {
            for (int index = locks.size() - 1; index >= 0; index--) {
                lockManager.release(locks.get(index));
            }
        }
        return checkedIn(outcome.reservationId(), outcome.alreadyCheckedIn());
    }

    private TerminalCheckinView checkedIn(long reservationId, boolean alreadyCheckedIn) {
        ReservationView view = repository.findView(reservationId);
        if (view == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        return TerminalCheckinView.checkedIn(view, alreadyCheckedIn);
    }

    private StoredChoice consumeChoice(String token) {
        if (token == null || token.isBlank() || token.length() > 256) {
            throw invalidChoice();
        }
        String stored = redisTemplate.execute(
                CONSUME_CHOICE_SCRIPT, List.of(CHOICE_KEY_PREFIX + token));
        if (stored == null) {
            throw invalidChoice();
        }
        try {
            return objectMapper.readValue(stored, StoredChoice.class);
        } catch (JsonProcessingException exception) {
            throw invalidChoice();
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException invalidChoice() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "选择凭证无效或已使用");
    }

    private record CheckinCandidate(long reservationId, LocalDateTime startTime) {
    }

    private record StoredChoice(long userId, long labId, List<Long> reservationIds) {
    }
}
