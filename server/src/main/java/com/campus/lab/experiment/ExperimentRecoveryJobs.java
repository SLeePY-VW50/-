package com.campus.lab.experiment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.experiment.recovery", name = "enabled", havingValue = "true")
public class ExperimentRecoveryJobs {

    private final ExperimentService experimentService;

    public ExperimentRecoveryJobs(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @Scheduled(
            fixedDelayString = "${app.experiment.recovery.fixed-delay:30s}",
            initialDelayString = "${app.experiment.recovery.initial-delay:20s}")
    public void recover() {
        experimentService.recoverInterrupted();
    }
}
