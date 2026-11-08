package com.campus.lab.statistics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.statistics.jobs", name = "enabled", havingValue = "true")
public class StatisticsSchedulingConfiguration {
}
