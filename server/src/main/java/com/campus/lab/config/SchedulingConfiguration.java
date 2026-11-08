package com.campus.lab.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.reservation.jobs",
        name = "enabled",
        havingValue = "true")
public class SchedulingConfiguration {
}
