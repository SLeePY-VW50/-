package com.campus.lab.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScheduleWorkerTest {

    @Test
    void scheduledTriggerDoesNotReenterAndUsesDedicatedExecutor() throws Exception {
        ScheduleService service = mock(ScheduleService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(30, TimeUnit.SECONDS)).isTrue();
            finished.countDown();
            return null;
        }).when(service).runDueBatches();
        ScheduleWorker worker = new ScheduleWorker(service, executor);
        try {
            worker.run();
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            worker.run();

            verify(service, timeout(1000).times(1)).runDueBatches();
            finish.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
            worker.run();
            verify(service, timeout(30_000).times(2)).runDueBatches();
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
