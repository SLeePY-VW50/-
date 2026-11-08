package com.campus.lab.resource;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ResourceConfiguration {

    @Bean
    Clock resourceClock(
            @org.springframework.beans.factory.annotation.Value("${app.clock.fixed-instant:}")
            String fixedInstant,
            @org.springframework.beans.factory.annotation.Value("${app.clock.zone:Asia/Shanghai}")
            String zone) {
        ZoneId zoneId = ZoneId.of(zone);
        return fixedInstant.isBlank()
                ? Clock.system(zoneId)
                : Clock.fixed(java.time.Instant.parse(fixedInstant), zoneId);
    }

    @Bean
    RuleValidator ruleValidator(Clock resourceClock) {
        return new RuleValidator(resourceClock);
    }
}
