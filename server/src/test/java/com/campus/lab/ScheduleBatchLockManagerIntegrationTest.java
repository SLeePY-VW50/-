package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.schedule.ScheduleBatchLock;
import com.campus.lab.schedule.ScheduleBatchLockManager;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(TestDatabaseConfiguration.class)
class ScheduleBatchLockManagerIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanKeys() {
        redisTemplate.delete("schedule:batch:" + DATE);
    }

    @Test
    void sameDateHasOneOwnerAndCloseDeletesOnlyOwnedToken() {
        ScheduleBatchLockManager manager = new ScheduleBatchLockManager(
                redisTemplate, Duration.ofSeconds(2));
        try {
            ScheduleBatchLock first = manager.tryAcquire(DATE).orElseThrow();

            assertThat(first.key()).isEqualTo("schedule:batch:" + DATE);
            assertThat(first.token()).isNotBlank();
            assertThat(manager.tryAcquire(DATE)).isEmpty();
            redisTemplate.opsForValue().set(first.key(), "another-owner", Duration.ofSeconds(30));
            first.close();
            assertThat(redisTemplate.opsForValue().get(first.key())).isEqualTo("another-owner");
        } finally {
            manager.close();
        }
    }

    @Test
    void leaseIsRenewedAndLostOwnershipIsDetected() throws Exception {
        ScheduleBatchLockManager manager = new ScheduleBatchLockManager(
                redisTemplate, Duration.ofMillis(300));
        try {
            ScheduleBatchLock lock = manager.tryAcquire(DATE).orElseThrow();
            Thread.sleep(900);

            assertThat(redisTemplate.hasKey(lock.key())).isTrue();
            assertThat(redisTemplate.getExpire(lock.key(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isPositive();
            lock.ensureHeld();

            redisTemplate.opsForValue().set(lock.key(), "new-owner", Duration.ofSeconds(30));
            Thread.sleep(600);
            assertThatThrownBy(lock::ensureHeld).hasMessageContaining("BATCH_LOCK_LOST");
            lock.close();
            assertThat(redisTemplate.opsForValue().get(lock.key())).isEqualTo("new-owner");
        } finally {
            manager.close();
        }
    }
}
