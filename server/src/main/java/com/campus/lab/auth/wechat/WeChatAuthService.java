package com.campus.lab.auth.wechat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.lab.auth.AuthService;
import com.campus.lab.auth.dto.LoginResponse;
import com.campus.lab.auth.entity.UserEntity;
import com.campus.lab.auth.mapper.UserMapper;
import com.campus.lab.auth.wechat.dto.WeChatBindRequest;
import com.campus.lab.auth.wechat.dto.WeChatLoginResponse;
import com.campus.lab.common.BusinessException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeChatAuthService {
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final int MAX_FAILURES = 5;
    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('exists', KEYS[2]) == 0 then
                return -1
            end
            local count = redis.call('incr', KEYS[1])
            redis.call('expire', KEYS[1], ARGV[2])
            if count >= tonumber(ARGV[1]) then
                redis.call('del', KEYS[2], KEYS[1])
            end
            return count
            """,
            Long.class);
    private static final DefaultRedisScript<Long> SUCCESS_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[3]) ~= ARGV[1] then
                return 0
            end
            if redis.call('get', KEYS[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """,
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final WeChatGateway gateway;
    private final UserMapper users;
    private final AuthService auth;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WeChatAuthService(WeChatGateway gateway, UserMapper users, AuthService auth,
            StringRedisTemplate redis, Clock clock) {
        this.gateway = gateway;
        this.users = users;
        this.auth = auth;
        this.redis = redis;
        this.clock = clock;
    }

    public WeChatLoginResponse login(String code) {
        String openid = gateway.openidForCode(code);
        UserEntity user = users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getWechatOpenid, openid));
        if (user != null) return WeChatLoginResponse.bound(auth.loginForUser(user));
        String ticket = newTicket();
        redis.opsForValue().set(ticketKey(ticket), openid, TICKET_TTL);
        return WeChatLoginResponse.unbound(ticket, TICKET_TTL.toSeconds());
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResponse bind(WeChatBindRequest request) {
        String ticket = request.bindTicket();
        String key = ticketKey(ticket);
        if (redis.opsForValue().get(key) == null) throw invalidTicket();
        String lockKey = lockKey(ticket);
        String lockToken = UUID.randomUUID().toString();
        acquireLock(lockKey, lockToken);
        try {
            String openid = redis.opsForValue().get(key);
            if (openid == null) throw invalidTicket();
            UserEntity user;
            try {
                user = auth.userByCredentials(request.account(), request.password());
            } catch (BusinessException exception) {
                registerFailure(ticket, key);
                throw exception;
            }
            if (user.getWechatOpenid() != null) throw alreadyBound();
            UserEntity other = users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                    .eq(UserEntity::getWechatOpenid, openid));
            if (other != null) throw alreadyBound();
            try {
                if (users.bindWechat(user.getUserId(), openid, LocalDateTime.now(clock)) != 1) {
                    throw alreadyBound();
                }
            } catch (DuplicateKeyException exception) {
                throw alreadyBound();
            }
            Long cleaned = redis.execute(SUCCESS_SCRIPT,
                    List.of(key, failureKey(ticket), lockKey), lockToken, openid);
            if (!Long.valueOf(1L).equals(cleaned)) throw invalidTicket();
            return auth.loginForUser(users.selectById(user.getUserId()));
        } finally {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
        }
    }

    private void registerFailure(String ticket, String key) {
        Long failures = redis.execute(FAILURE_SCRIPT, List.of(failureKey(ticket), key),
                Integer.toString(MAX_FAILURES), Long.toString(TICKET_TTL.toSeconds()));
        if (failures != null && failures < 0) throw invalidTicket();
    }

    private void acquireLock(String key, String token) {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (true) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, LOCK_LEASE))) return;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw invalidTicket();
            try {
                Thread.sleep(Math.max(1, Math.min(25, remainingNanos / 1_000_000)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw invalidTicket();
            }
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String ticketKey(String ticket) { return "auth:wechat:bind:" + ticket; }
    private static String failureKey(String ticket) { return "auth:wechat:bind-fail:" + ticket; }
    private static String lockKey(String ticket) { return "auth:wechat:bind-lock:" + ticket; }
    private static BusinessException invalidTicket() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "微信绑定凭证无效或已过期，请重新登录");
    }
    private static BusinessException alreadyBound() {
        return new BusinessException(HttpStatus.CONFLICT, "该学号或微信已绑定其他账号");
    }
}
