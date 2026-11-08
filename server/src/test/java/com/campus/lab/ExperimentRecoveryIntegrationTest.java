package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.experiment.ExperimentService;
import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleAlgorithmExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@TestPropertySource(properties = {
        "app.experiment.recovery.enabled=true", "app.experiment.recovery.batch-limit=8",
        "app.experiment.recovery.fixed-delay=3600s",
        "app.experiment.recovery.initial-delay=3600s"})
class ExperimentRecoveryIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final long SEED = 20261024L;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoSpyBean
    private ThreadPoolTaskExecutor experimentExecutor;

    @MockitoSpyBean
    private ScheduleAlgorithmExecutor algorithmExecutor;

    @MockitoSpyBean
    private com.campus.lab.experiment.ExperimentRepository experimentRepository;

    @MockitoSpyBean
    private com.campus.lab.experiment.ExperimentBatchLockManager experimentBatchLockManager;

    @AfterEach
    void cleanRecoveryFixtures() {
        Mockito.reset(experimentExecutor, algorithmExecutor, experimentRepository,
                experimentBatchLockManager);
        jdbcTemplate.update("""
                DELETE e FROM t_algorithm_experiment e
                JOIN t_schedule_task t ON t.task_id = e.task_id
                WHERE t.schedule_batch_no LIKE 'EXP-%'
                """);
        jdbcTemplate.update("""
                DELETE r FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.schedule_batch_no LIKE 'EXP-%'
                """);
        jdbcTemplate.update("DELETE FROM t_schedule_task WHERE schedule_batch_no LIKE 'EXP-%'");
        Set<String> keys = scanKeys("experiment:batch:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void rejectedRecoveryKeepsRecoverableStateReleasesLockAndRetriesNextRound() throws Exception {
        insertTask("EXP-RECOVERY-REJECT", "FCFS", "RUNNING", 0, SEED);
        Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("queue full"))
                .doCallRealMethod().when(experimentExecutor).execute(Mockito.any(Runnable.class));

        recover();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM t_schedule_task WHERE schedule_batch_no = 'EXP-RECOVERY-REJECT'
                """, String.class)).isEqualTo("PENDING");
        assertThat(scanKeys("experiment:batch:*")).isEmpty();

        recover();
        awaitSucceeded("EXP-RECOVERY-REJECT");
    }

    @Test
    void followingRecoveryRoundPicksNinthBatchWithoutRestart() throws Exception {
        for (int index = 1; index <= 9; index++) {
            insertTask("EXP-LIMIT-" + index, "FCFS", "PENDING", 0, SEED + index);
        }

        int rounds = 0;
        int succeeded = 0;
        while (rounds < 20 && succeeded < 9) {
            recover();
            rounds++;
            Thread.sleep(50);
            succeeded = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM t_schedule_task
                    WHERE schedule_batch_no LIKE 'EXP-LIMIT-%' AND status = 'SUCCEEDED'
                    """, Integer.class);
        }

        assertThat(rounds).isGreaterThan(1);
        assertThat(succeeded).isEqualTo(9);
    }

    @Test
    void concurrentServiceInstancesCannotResetOrExecuteTheSameRunningBatch() throws Exception {
        insertTask("EXP-LOCKED", "FCFS", "SUCCEEDED", 0, SEED);
        insertTask("EXP-LOCKED", "GREEDY", "RUNNING", 0, SEED);
        insertTask("EXP-LOCKED", "GENETIC", "PENDING", 0, SEED);
        insertTask("EXP-LOCKED", "GENETIC", "PENDING", 1, SEED + 1);
        CountDownLatch algorithmEntered = new CountDownLatch(1);
        CountDownLatch allowAlgorithm = new CountDownLatch(1);
        AtomicInteger greedyCalls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            greedyCalls.incrementAndGet();
            algorithmEntered.countDown();
            assertThat(allowAlgorithm.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(algorithmExecutor).execute(
                Mockito.eq(AlgorithmType.GREEDY), Mockito.any(), Mockito.any());
        ExperimentService first = applicationContext.getBean(ExperimentService.class);
        ExperimentService second = applicationContext.getAutowireCapableBeanFactory()
                .createBean(ExperimentService.class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstRecovery = new Thread(() -> {
            try {
                first.recoverInterrupted();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        }, "first-recovery");

        firstRecovery.start();
        assertThat(algorithmEntered.await(30, TimeUnit.SECONDS)).isTrue();
        second.recoverInterrupted();
        allowAlgorithm.countDown();
        firstRecovery.join(30_000);
        awaitSucceeded("EXP-LOCKED");
        awaitLockReleased("EXP-LOCKED");

        assertThat(failure.get()).isNull();
        assertThat(greedyCalls).hasValue(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT task_id, COUNT(*) AS metric_count FROM t_algorithm_experiment
                    GROUP BY task_id HAVING metric_count <> 1
                ) duplicates
                """, Integer.class)).isZero();
        assertThat(scanKeys("experiment:batch:*")).isEmpty();
    }

    @Test
    void newBatchLockIsHeldBeforeCreateSoRecoveryCannotRunAnUnsubmittedBatch() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        CountDownLatch algorithmEntered = new CountDownLatch(1);
        CountDownLatch allowAlgorithm = new CountDownLatch(1);
        AtomicInteger fcfsCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean pauseUsed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            String batchNo = invocation.getArgument(0);
            int tasks = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM t_schedule_task WHERE schedule_batch_no = ?
                    """, Integer.class, batchNo);
            if (tasks > 0 && pauseUsed.compareAndSet(false, true)) {
                created.countDown();
                assertThat(allowCreate.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(experimentBatchLockManager).tryAcquire(Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            String batchNo = jdbcTemplate.queryForObject("""
                    SELECT schedule_batch_no FROM t_schedule_task
                    WHERE schedule_batch_no LIKE 'EXP-%' ORDER BY task_id DESC LIMIT 1
                    """, String.class);
            if (batchNo != null && pauseUsed.compareAndSet(false, true)) {
                created.countDown();
                assertThat(allowCreate.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(experimentExecutor).execute(Mockito.any(Runnable.class));
        Mockito.doAnswer(invocation -> {
            fcfsCalls.incrementAndGet();
            algorithmEntered.countDown();
            assertThat(allowAlgorithm.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(algorithmExecutor).execute(
                Mockito.eq(AlgorithmType.FCFS), Mockito.any(), Mockito.any());
        java.util.concurrent.ExecutorService apiExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        String token = loginAndGetToken("A1001", "Lab@2026");
        java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> apiResult =
                apiExecutor.submit(() -> mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/experiments/run")
                                .header("Authorization", "Bearer " + token)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"dataScale\":50,\"seed\":20261024}"))
                        .andReturn());
        try {
            assertThat(created.await(30, TimeUnit.SECONDS)).isTrue();
            applicationContext.getBean(ExperimentService.class).recoverInterrupted();
            boolean recoveryExecuted = algorithmEntered.await(200, TimeUnit.MILLISECONDS);
            allowCreate.countDown();
            org.springframework.test.web.servlet.MvcResult result = apiResult.get(30, TimeUnit.SECONDS);

            assertThat(recoveryExecuted).isFalse();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            allowAlgorithm.countDown();
            String batchNo = jdbcTemplate.queryForObject("""
                    SELECT schedule_batch_no FROM t_schedule_task
                    WHERE schedule_batch_no LIKE 'EXP-%' ORDER BY task_id DESC LIMIT 1
                    """, String.class);
            awaitSucceeded(batchNo);
            awaitLockReleased(batchNo);
            assertThat(fcfsCalls).hasValue(1);
        } finally {
            allowCreate.countDown();
            allowAlgorithm.countDown();
            apiExecutor.shutdownNow();
        }
    }

    private void awaitLockReleased(String batchNo) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!scanKeys("experiment:batch:" + batchNo).isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(scanKeys("experiment:batch:" + batchNo)).isEmpty();
    }

    private void recover() throws Exception {
        assertThat(applicationContext.containsBean("experimentRecoveryJobs")).isTrue();
        Object jobs = applicationContext.getBean("experimentRecoveryJobs");
        jobs.getClass().getMethod("recover").invoke(jobs);
    }

    private long insertTask(String batchNo, String algorithm, String status, int repeat, long seed) {
        jdbcTemplate.update("""
                INSERT INTO t_schedule_task
                    (schedule_batch_no, algorithm_type, is_primary, dataset_id,
                     demand_snapshot, resource_snapshot, parameters, random_seed, repeat_index,
                     runtime_environment, status)
                VALUES (?, ?, FALSE, ?, ?, '[]', '{\"populationSize\":4,\"generations\":5}',
                        ?, ?, '{}', ?)
                """, batchNo, algorithm, batchNo + "-DATA",
                "{\"dataScale\":50,\"seed\":" + seed
                        + ",\"date\":\"2026-10-26\",\"demands\":[]}",
                seed, repeat, status);
        return jdbcTemplate.queryForObject("""
                SELECT MAX(task_id) FROM t_schedule_task WHERE schedule_batch_no = ?
                """, Long.class, batchNo);
    }

    private void awaitSucceeded(String batchNo) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Map<String, Object> status = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COALESCE(SUM(status = 'SUCCEEDED'), 0) AS succeeded,
                           COALESCE(SUM(status = 'FAILED'), 0) AS failed
                    FROM t_schedule_task WHERE schedule_batch_no = ?
                    """, batchNo);
            assertThat(((Number) status.get("failed")).intValue()).isZero();
            if (((Number) status.get("total")).intValue() > 0
                    && ((Number) status.get("total")).intValue()
                    == ((Number) status.get("succeeded")).intValue()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Recovery did not finish: " + batchNo);
    }
}
