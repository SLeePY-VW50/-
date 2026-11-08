package com.campus.lab.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.TestDatabaseConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(TestDatabaseConfiguration.class)
class ClockConfigurationIntegrationTest {

    @Autowired
    private Clock resourceClock;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResourceConfiguration.class);

    @Test
    void testProfileUsesFixedShanghaiClock() {
        assertThat(resourceClock.instant()).isEqualTo(Instant.parse("2026-10-24T08:00:00+08:00"));
        assertThat(resourceClock.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void localAndProductionDoNotUseFixedClock() {
        for (String profile : new String[] {"local", "prod"}) {
            contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        Clock clock = context.getBean(Clock.class);
                        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
                        assertThat(clock.instant()).isCloseTo(
                                Instant.now(), org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
                    });
        }
    }
}
