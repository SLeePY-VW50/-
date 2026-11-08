package com.campus.lab.experiment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.experiment.recovery", name = "enabled", havingValue = "true")
public class ExperimentRecoverySchedulingConfiguration {
}
