package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RedisLockManager implements LockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration waitTime;
    private final Duration leaseTime;

    public RedisLockManager(
            StringRedisTemplate redisTemplate,
            @Value("${app.reservation.lock.wait:3s}") Duration waitTime,
            @Value("${app.reservation.lock.lease:30s}") Duration leaseTime) {
        if (waitTime.isNegative() || leaseTime.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalStateException("预约锁等待时间不能为负，租期不得少于30秒");
        }
        this.redisTemplate = redisTemplate;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
    }

    @Override
    public ReservationLock acquire(long labId, LocalDate date) {
        return acquire(labId, date, System.nanoTime() + waitTime.toNanos());
    }

    private ReservationLock acquire(long labId, LocalDate date, long deadline) {
        ReservationLock lock = new ReservationLock(
                "resv:lab:" + labId + ":" + date, UUID.randomUUID().toString());
        while (true) {
            Boolean acquired;
            try {
                acquired = redisTemplate.opsForValue().setIfAbsent(lock.key(), lock.token(), leaseTime);
            } catch (RuntimeException exception) {
                throw peakLoad();
            }
            if (Boolean.TRUE.equals(acquired)) {
                return lock;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw peakLoad();
            }
            try {
                Thread.sleep(Math.max(1, Math.min(50, remainingNanos / 1_000_000)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw peakLoad();
            }
        }
    }

    @Override
    public List<ReservationLock> acquireAll(List<LabLockKey> keys) {
        List<LabLockKey> sortedKeys = keys.stream().distinct()
                .sorted(Comparator.comparingLong(LabLockKey::labId)
                        .thenComparing(LabLockKey::date))
                .toList();
        long deadline = System.nanoTime() + waitTime.toNanos();
        List<ReservationLock> acquired = new ArrayList<>(sortedKeys.size());
        try {
            for (LabLockKey key : sortedKeys) {
                acquired.add(acquire(key.labId(), key.date(), deadline));
            }
            return List.copyOf(acquired);
        } catch (RuntimeException exception) {
            releaseReverse(acquired);
            throw exception;
        }
    }

    @Override
    public void release(ReservationLock lock) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lock.key()), lock.token());
        } catch (RuntimeException exception) {
            log.warn("释放预约锁失败: {}", lock.key(), exception);
        }
    }

    private void releaseReverse(List<ReservationLock> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            release(acquired.get(index));
        }
    }

    private static BusinessException peakLoad() {
        return new BusinessException(HttpStatus.CONFLICT, "当前预约高峰，请稍后重试");
    }
}
