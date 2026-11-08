package com.campus.lab.reservation;

import com.campus.lab.checkin.CheckinRuleService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ReservationScheduledJobs.class);

    private final JdbcTemplate jdbcTemplate;
    private final ReservationRepository repository;
    private final ReservationScheduledTransactionService transactionService;
    private final CheckinRuleService ruleService;
    private final LockManager lockManager;
    private final Clock clock;
    private final int batchSize;

    public ReservationScheduledJobs(
            JdbcTemplate jdbcTemplate,
            ReservationRepository repository,
            ReservationScheduledTransactionService transactionService,
            CheckinRuleService ruleService,
            LockManager lockManager,
            Clock clock,
            @Value("${app.reservation.jobs.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalStateException("自动任务批大小必须在1到1000之间");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.transactionService = transactionService;
        this.ruleService = ruleService;
        this.lockManager = lockManager;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.reservation.jobs.fixed-delay:60s}",
            initialDelayString = "${app.reservation.jobs.initial-delay:15s}")
    public void run() {
        runCategory("no-show", this::processNoShows);
        runCategory("ended", this::processEndedReservations);
        runCategory("pending-expiration", this::processExpiredPendingReservations);
    }

    public void processNoShows() {
        LocalDateTime now = LocalDateTime.now(clock);
        int graceMinutes = ruleService.rules().graceMinutes();
        processBatches("no-show",
                (afterId, limit) -> jdbcTemplate.queryForList("""
                        SELECT resv_id FROM t_reservation
                        WHERE status = 'APPROVED' AND checkin_time IS NULL
                          AND start_time IS NOT NULL
                          AND start_time < DATE_SUB(?, INTERVAL ? MINUTE)
                          AND resv_id > ?
                        ORDER BY resv_id LIMIT ?
                        """, Long.class, now, graceMinutes, afterId, limit),
                this::processNoShow);
    }

    public void processEndedReservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        processBatches("ended",
                (afterId, limit) -> jdbcTemplate.queryForList("""
                        SELECT resv_id FROM t_reservation
                        WHERE status = 'IN_USE' AND end_time <= ? AND resv_id > ?
                        ORDER BY resv_id LIMIT ?
                        """, Long.class, now, afterId, limit),
                transactionService::completeEnded);
    }

    public void processExpiredPendingReservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        processBatches("pending-expiration",
                (afterId, limit) -> jdbcTemplate.queryForList("""
                        SELECT resv_id FROM t_reservation
                        WHERE status = 'PENDING_SCHEDULE' AND resv_id > ?
                          AND DATE_ADD(TIMESTAMP(request_date, '00:00:00'),
                                       INTERVAL TIME_TO_SEC(request_window_end) SECOND) <= ?
                        ORDER BY resv_id LIMIT ?
                        """, Long.class, afterId, now, limit),
                transactionService::expirePending);
    }

    private void processNoShow(long reservationId) {
        ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
        if (target == null || target.labId() == null) {
            return;
        }
        LabLockKey key = new LabLockKey(target.labId(), target.date());
        List<ReservationLock> locks = lockManager.acquireAll(List.of(key));
        try {
            transactionService.noShow(reservationId, key);
        } finally {
            for (int index = locks.size() - 1; index >= 0; index--) {
                lockManager.release(locks.get(index));
            }
        }
    }

    private void processBatches(String category, CandidateQuery query, ItemProcessor processor) {
        long afterId = 0;
        while (true) {
            List<Long> reservationIds = query.load(afterId, batchSize);
            if (reservationIds.isEmpty()) {
                return;
            }
            for (long reservationId : reservationIds) {
                try {
                    processor.process(reservationId);
                } catch (RuntimeException exception) {
                    log.warn("预约自动任务单条失败: category={}, resvId={}, errorType={}",
                            category, reservationId, exception.getClass().getSimpleName());
                }
                afterId = reservationId;
            }
            if (reservationIds.size() < batchSize) {
                return;
            }
        }
    }

    private void runCategory(String category, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException exception) {
            log.warn("预约自动任务批次失败: category={}, errorType={}",
                    category, exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface CandidateQuery {
        List<Long> load(long afterId, int limit);
    }

    @FunctionalInterface
    private interface ItemProcessor {
        void process(long reservationId);
    }
}
