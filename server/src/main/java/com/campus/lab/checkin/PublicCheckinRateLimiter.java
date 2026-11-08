package com.campus.lab.checkin;

import com.campus.lab.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PublicCheckinRateLimiter {

    private static final DefaultRedisScript<Long> ACCOUNT_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('incr', KEYS[1])
            if count == 1 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            if count > tonumber(ARGV[2]) then
                return 1
            end
            return 0
            """,
            Long.class);
    private static final DefaultRedisScript<Long> SESSION_SCRIPT = new DefaultRedisScript<>(
            """
            local codeCount = redis.call('incr', KEYS[1])
            if codeCount == 1 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            local addressCount = redis.call('incr', KEYS[2])
            if addressCount == 1 then
                redis.call('expire', KEYS[2], ARGV[1])
            end
            if codeCount > tonumber(ARGV[2]) then
                return 1
            end
            if addressCount > tonumber(ARGV[3]) then
                return 2
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long accountLimit;
    private final long sessionCodeLimit;
    private final long sessionAddressLimit;
    private final long windowSeconds;

    public PublicCheckinRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.checkin.public.account-limit:60}") long accountLimit,
            @Value("${app.checkin.public.session-code-limit:10}") long sessionCodeLimit,
            @Value("${app.checkin.public.session-address-limit:30}") long sessionAddressLimit,
            @Value("${app.checkin.public.window:60s}") Duration window) {
        if (accountLimit <= 0 || sessionCodeLimit <= 0 || sessionAddressLimit <= 0
                || window.isZero() || window.isNegative() || window.toSeconds() < 1) {
            throw new IllegalStateException("公共签到限流阈值和窗口必须为正数");
        }
        this.redisTemplate = redisTemplate;
        this.accountLimit = accountLimit;
        this.sessionCodeLimit = sessionCodeLimit;
        this.sessionAddressLimit = sessionAddressLimit;
        this.windowSeconds = window.toSeconds();
    }

    public void checkAccount(long deviceId, String remoteAddress) {
        String key = "checkin:public:account:" + deviceId + ":" + digest(remoteAddress);
        Long limited = redisTemplate.execute(
                ACCOUNT_SCRIPT,
                List.of(key),
                Long.toString(windowSeconds),
                Long.toString(accountLimit));
        if (Long.valueOf(1L).equals(limited)) {
            throw tooManyRequests();
        }
    }

    public void checkSession(String terminalCode, String remoteAddress) {
        String codeKey = "checkin:public:session:code:" + digest(normalize(terminalCode));
        String addressKey = "checkin:public:session:address:" + digest(remoteAddress);
        Long limited = redisTemplate.execute(
                SESSION_SCRIPT,
                List.of(codeKey, addressKey),
                Long.toString(windowSeconds),
                Long.toString(sessionCodeLimit),
                Long.toString(sessionAddressLimit));
        if (limited != null && limited > 0) {
            throw tooManyRequests();
        }
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            String normalized = value == null ? "" : value;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少SHA-256摘要算法", exception);
        }
    }

    private static BusinessException tooManyRequests() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
    }
}
