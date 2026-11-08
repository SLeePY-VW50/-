package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisTestIsolationIntegrationTest extends AbstractReservationNonTransactionalTest {

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @Test
    void testDatabaseIsFifteenAndScanCleanupPreservesUnrelatedKeys() {
        assertThat(redisConnectionFactory.getDatabase()).isEqualTo(15);
        String suffix = UUID.randomUUID().toString();
        String checkinKey = "checkin:isolation:" + suffix;
        String reservationKey = "resv:isolation:" + suffix;
        String unrelatedKey = "unrelated:isolation:" + suffix;
        try {
            redisTemplate.opsForValue().set(checkinKey, "1");
            redisTemplate.opsForValue().set(reservationKey, "1");
            redisTemplate.opsForValue().set(unrelatedKey, "keep");

            assertThat(scanKeys("checkin:*")).contains(checkinKey);
            cleanOwnedRedisKeys();

            assertThat(redisTemplate.hasKey(checkinKey)).isFalse();
            assertThat(redisTemplate.hasKey(reservationKey)).isFalse();
            assertThat(redisTemplate.hasKey(unrelatedKey)).isTrue();
        } finally {
            redisTemplate.delete(Set.of(checkinKey, reservationKey, unrelatedKey));
        }
    }
}
