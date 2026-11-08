package com.campus.lab.schedule;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.schedule.worker", name = "enabled", havingValue = "true")
public class ScheduleWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduleWorker.class);

    private final ScheduleService scheduleService;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public ScheduleWorker(
            ScheduleService scheduleService,
            @Qualifier("scheduleExecutor") Executor executor) {
        this.scheduleService = scheduleService;
        this.executor = executor;
    }

    @Scheduled(
            fixedDelayString = "${app.schedule.worker.fixed-delay:60s}",
            initialDelayString = "${app.schedule.worker.initial-delay:15s}")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    scheduleService.runDueBatches();
                } catch (RuntimeException exception) {
                    log.warn("排期Worker执行失败: {}", exception.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }
}
