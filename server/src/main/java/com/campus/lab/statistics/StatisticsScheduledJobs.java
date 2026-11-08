package com.campus.lab.statistics;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.statistics.jobs", name = "enabled", havingValue = "true")
public class StatisticsScheduledJobs {

    private final StatisticsRepository repository;
    private final StatisticsService statisticsService;
    private final Clock clock;

    public StatisticsScheduledJobs(
            StatisticsRepository repository, StatisticsService statisticsService, Clock clock) {
        this.repository = repository;
        this.statisticsService = statisticsService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void refreshDaily() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        java.util.List<StatisticsViews.LabDateTarget> targets = new java.util.ArrayList<>();
        for (long labId : repository.listAllLabIds()) {
            targets.add(new StatisticsViews.LabDateTarget(labId, yesterday));
        }
        targets.addAll(repository.listBackfillStarts(yesterday));
        statisticsService.refreshDailyBatch(targets);
    }
}
