package com.campus.lab.schedule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.schedule.worker", name = "enabled", havingValue = "true")
public class ScheduleWorkerSchedulingConfiguration {
}
