package com.campus.lab.schedule;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ScheduleBatchLockManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScheduleBatchLockManager.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration leaseTime;
    private final ScheduledExecutorService renewalExecutor;

    public ScheduleBatchLockManager(
            StringRedisTemplate redisTemplate,
            @Value("${app.schedule.batch-lock.lease:2m}") Duration leaseTime) {
        if (leaseTime.isZero() || leaseTime.isNegative()) {
            throw new IllegalArgumentException("排期批次锁租期必须大于零");
        }
        this.redisTemplate = redisTemplate;
        this.leaseTime = leaseTime;
        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "schedule-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Optional<ScheduleBatchLock> tryAcquire(LocalDate date) {
        String key = "schedule:batch:" + date;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, leaseTime);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        ScheduleBatchLock lock = new ScheduleBatchLock(
                key, token, () -> token.equals(redisTemplate.opsForValue().get(key)), this::release);
        long renewalDelay = Math.max(50, leaseTime.toMillis() / 3);
        ScheduledFuture<?> renewal = renewalExecutor.scheduleWithFixedDelay(
                () -> renew(lock), renewalDelay, renewalDelay, TimeUnit.MILLISECONDS);
        lock.renewal(renewal);
        return Optional.of(lock);
    }

    private void renew(ScheduleBatchLock lock) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT, List.of(lock.key()), lock.token(), Long.toString(leaseTime.toMillis()));
            if (!Long.valueOf(1).equals(renewed)) {
                lock.markLost();
            }
        } catch (RuntimeException exception) {
            lock.markLost();
            log.warn("排期批次锁续约失败: {} ({})", lock.key(), exception.getClass().getSimpleName());
        }
    }

    private void release(ScheduleBatchLock lock) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lock.key()), lock.token());
        } catch (RuntimeException exception) {
            log.warn("释放排期批次锁失败: {} ({})", lock.key(), exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    @Override
    public void close() {
        renewalExecutor.shutdownNow();
    }
}
