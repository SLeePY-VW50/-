package com.campus.lab.checkin;

import com.campus.lab.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TerminalAccountService {

    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('exists', KEYS[2]) == 1 then
                return -1
            end
            local count = redis.call('incr', KEYS[1])
            if count == 1 then
                redis.call('expire', KEYS[1], ARGV[2])
            end
            if count >= tonumber(ARGV[1]) then
                redis.call('set', KEYS[2], '1', 'EX', ARGV[2])
                redis.call('del', KEYS[1])
            end
            return count
            """,
            Long.class);
    private static final DefaultRedisScript<Long> CLEAR_FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('exists', KEYS[2]) == 1 then
                return 0
            end
            redis.call('del', KEYS[1])
            return 1
            """,
            Long.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final CheckinRuleService ruleService;
    private final String unavailableAccountHash;

    public TerminalAccountService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            PasswordEncoder passwordEncoder,
            CheckinRuleService ruleService) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.ruleService = ruleService;
        this.unavailableAccountHash = passwordEncoder.encode("terminal-unavailable-account-placeholder");
    }

    public long authenticate(long deviceId, String account, String password) {
        String normalizedAccount = normalizeAccount(account);
        String candidatePassword = password == null ? "" : password;
        List<AccountRecord> accounts = jdbcTemplate.query("""
                SELECT u.user_id, u.account, u.password_hash, u.status,
                       GROUP_CONCAT(r.role_code ORDER BY r.role_code) AS role_codes
                FROM sys_user u
                LEFT JOIN sys_user_role ur ON ur.user_id = u.user_id
                LEFT JOIN sys_role r ON r.role_id = ur.role_id
                WHERE u.account = ?
                GROUP BY u.user_id, u.account, u.password_hash, u.status
                """,
                (rs, rowNum) -> new AccountRecord(
                        rs.getLong("user_id"), rs.getString("account"),
                        rs.getString("password_hash"), rs.getString("status"),
                        rs.getString("role_codes")),
                normalizedAccount);
        AccountRecord user = accounts.isEmpty() ? null : accounts.getFirst();
        String identity = user == null
                ? "anonymous:" + digest(normalizedAccount)
                : "user:" + user.userId();
        String failureKey = "checkin:terminal:fail:" + deviceId + ":" + identity;
        String lockKey = "checkin:terminal:lock:" + deviceId + ":" + identity;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw invalidCredentials();
        }

        boolean passwordLengthValid = candidatePassword.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean passwordMatches = false;
        if (passwordLengthValid) {
            try {
                passwordMatches = passwordEncoder.matches(
                        candidatePassword, user == null ? unavailableAccountHash : user.passwordHash());
            } catch (IllegalArgumentException exception) {
                passwordMatches = false;
            }
        }
        boolean roleAllowed = user != null && user.roleCodes() != null
                && java.util.Arrays.stream(user.roleCodes().split(","))
                        .anyMatch(role -> "STUDENT".equals(role) || "TEACHER".equals(role));
        if (user == null || !passwordMatches || !"ACTIVE".equals(user.status()) || !roleAllowed) {
            CheckinRuleService.CheckinRules rules = ruleService.rules();
            redisTemplate.execute(
                    FAILURE_SCRIPT,
                    List.of(failureKey, lockKey),
                    Integer.toString(rules.maxFailedAttempts()),
                    Long.toString(rules.lockoutMinutes() * 60L));
            throw invalidCredentials();
        }
        Long cleared = redisTemplate.execute(CLEAR_FAILURE_SCRIPT, List.of(failureKey, lockKey));
        if (!Long.valueOf(1L).equals(cleared)) {
            throw invalidCredentials();
        }
        return user.userId();
    }

    private static String normalizeAccount(String account) {
        String value = account == null ? "" : account.trim();
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String digest(String account) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少SHA-256摘要算法", exception);
        }
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }

    private record AccountRecord(
            long userId, String canonicalAccount, String passwordHash, String status, String roleCodes) {
    }
}
