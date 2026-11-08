package com.campus.lab.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.TestDatabaseConfiguration;
import com.campus.lab.common.BusinessException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(TestDatabaseConfiguration.class)
class RedisLockManagerIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private LockManager lockManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<ReservationLock> heldLocks = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanOwnedKeys() {
        for (ReservationLock lock : heldLocks) {
            lockManager.release(lock);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void sameLabDateConflictsUntilOwnerReleases() {
        long labId = uniqueLabId();
        ReservationLock first = acquire(labId, DATE);

        assertThat(first.key()).isEqualTo("resv:lab:" + labId + ":" + DATE);
        assertThat(first.token()).isNotBlank();
        assertThat(redisTemplate.getExpire(first.key(), TimeUnit.MILLISECONDS))
                .isBetween(25_000L, 60_000L);
        assertThatThrownBy(() -> lockManager.acquire(labId, DATE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高峰");

        release(first);
        ReservationLock reacquired = acquire(labId, DATE);
        assertThat(reacquired.token()).isNotEqualTo(first.token());
    }

    @Test
    void releaseUsesTokenComparisonAndCannotDeleteAnotherOwnersLock() {
        long labId = uniqueLabId();
        ReservationLock owner = acquire(labId, DATE);

        lockManager.release(new ReservationLock(owner.key(), UUID.randomUUID().toString()));

        assertThat(redisTemplate.opsForValue().get(owner.key())).isEqualTo(owner.token());
        release(owner);
        assertThat(redisTemplate.hasKey(owner.key())).isFalse();
    }

    @Test
    void batchAcquisitionReleasesEarlierLocksWhenAnyKeyIsUnavailable() {
        long freeLabId = uniqueLabId();
        long blockedLabId = freeLabId + 1;
        ReservationLock blocked = acquire(blockedLabId, DATE);

        assertThatThrownBy(() -> lockManager.acquireAll(List.of(
                        new LabLockKey(freeLabId, DATE), new LabLockKey(blockedLabId, DATE))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高峰");

        release(blocked);
        acquire(freeLabId, DATE);
    }

    @Test
    void batchAcquisitionUsesOneSharedWaitDeadline() throws Exception {
        long firstLabId = uniqueLabId();
        long secondLabId = firstLabId + 1;
        ReservationLock firstLock = acquire(firstLabId, DATE);
        ReservationLock secondLock = acquire(secondLabId, DATE);
        Duration waitTime = Duration.ofSeconds(3);
        long waitNanos = waitTime.toNanos();
        long firstWaitNanos = Duration.ofSeconds(1).toNanos();
        AtomicLong firstAttemptNanos = new AtomicLong();
        AtomicLong secondAttemptNanos = new AtomicLong();
        AtomicLong secondReleaseNanos = new AtomicLong();
        AtomicLong sharedDeadline = new AtomicLong();
        CountDownLatch firstAttempted = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        List<ReservationLock> acquiredByShortManager = new CopyOnWriteArrayList<>();
        StringRedisTemplate controlledRedis = Mockito.spy(redisTemplate);
        ValueOperations<String, String> operations = Mockito.spy(redisTemplate.opsForValue());
        Mockito.when(controlledRedis.opsForValue()).thenReturn(operations);
        RedisLockManager shortWaitManager = new RedisLockManager(
                controlledRedis, waitTime, Duration.ofSeconds(30));
        Mockito.doAnswer(invocation -> {
            Boolean acquired = (Boolean) invocation.callRealMethod();
            String key = invocation.getArgument(0);
            if (key.equals(firstLock.key())) {
                long attemptedAt = System.nanoTime();
                firstAttemptNanos.compareAndSet(0, attemptedAt);
                sharedDeadline.compareAndSet(0, attemptedAt + waitNanos);
                firstAttempted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            if (key.equals(secondLock.key())) {
                secondAttemptNanos.compareAndSet(0, System.nanoTime());
                secondAttempted.countDown();
                assertThat(releaseSecond.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return acquired;
        }).when(operations).setIfAbsent(
                Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Throwable> acquisition = executor.submit(() -> {
                try {
                    acquiredByShortManager.addAll(shortWaitManager.acquireAll(List.of(
                            new LabLockKey(firstLabId, DATE), new LabLockKey(secondLabId, DATE))));
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });
            assertThat(firstAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            awaitDeadline(firstAttemptNanos.get() + firstWaitNanos);
            release(firstLock);
            releaseFirst.countDown();
            assertThat(secondAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAttemptNanos.get()).isLessThan(sharedDeadline.get());

            awaitDeadline(sharedDeadline.get());
            secondReleaseNanos.set(System.nanoTime());
            release(secondLock);
            releaseSecond.countDown();
            Throwable outcome = acquisition.get(30, TimeUnit.SECONDS);

            assertThat(outcome).isInstanceOf(BusinessException.class).hasMessageContaining("高峰");
            assertThat(secondReleaseNanos.get()).isGreaterThanOrEqualTo(sharedDeadline.get());
            assertThat(secondReleaseNanos.get() - secondAttemptNanos.get()).isLessThan(waitNanos);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            if (heldLocks.contains(firstLock)) {
                release(firstLock);
            }
            if (heldLocks.contains(secondLock)) {
                release(secondLock);
            }
            try {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                acquiredByShortManager.forEach(lockManager::release);
            }
        }
    }

    private static void awaitDeadline(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
    }

    private ReservationLock acquire(long labId, LocalDate date) {
        ReservationLock lock = lockManager.acquire(labId, date);
        heldLocks.add(lock);
        if (!keys.contains(lock.key())) {
            keys.add(lock.key());
        }
        return lock;
    }

    private void release(ReservationLock lock) {
        lockManager.release(lock);
        heldLocks.remove(lock);
    }

    private static long uniqueLabId() {
        return 900_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000L);
    }
}
