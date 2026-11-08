package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.test.context.TestPropertySource(
        properties = "app.experiment.recovery.batch-limit=1")
class ExperimentIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final long SEED = 20261024L;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor experimentExecutor;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.campus.lab.schedule.ScheduleAlgorithmExecutor algorithmExecutor;

    @org.junit.jupiter.api.AfterEach
    void resetExperimentSpies() {
        org.mockito.Mockito.reset(experimentExecutor, algorithmExecutor);
    }

    @AfterEach
    void cleanExperiments() {
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
    }

    @Test
    void seededRunsAreReproducibleAndNeverTouchProductionSchedulingFacts() throws Exception {
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        String reader = loginAndGetToken("L1001", "Lab@2026");
        Map<String, Object> productionBefore = productionFingerprint();

        JsonNode firstRun = runExperiment(administrator, 50, null);
        assertThat(firstRun.path("seed").asLong()).isEqualTo(SEED);
        assertThat(firstRun.path("status").asText()).isIn("PENDING", "RUNNING", "SUCCEEDED");
        JsonNode first = awaitCompleted(reader, firstRun.path("batchNo").asText());
        assertSeededBatch(first, 50, SEED);

        JsonNode secondRun = runExperiment(administrator, 50, SEED);
        JsonNode second = awaitCompleted(reader, secondRun.path("batchNo").asText());
        assertThat(snapshot(second, "demand_snapshot")).isEqualTo(snapshot(first, "demand_snapshot"));
        assertThat(snapshot(second, "resource_snapshot")).isEqualTo(snapshot(first, "resource_snapshot"));
        assertSeededBatch(second, 50, SEED);

        JsonNode different = awaitCompleted(
                reader, runExperiment(administrator, 50, SEED + 100).path("batchNo").asText());
        assertThat(snapshot(different, "demand_snapshot"))
                .isNotEqualTo(snapshot(first, "demand_snapshot"));

        assertThat(productionFingerprint()).isEqualTo(productionBefore);
    }

    @Test
    void onlySystemAdministratorCanStartAndOnlyAdministratorsCanReadExperiments() throws Exception {
        for (String account : List.of("L1001", "20240001", "T1001")) {
            String token = loginAndGetToken(account, "Lab@2026");
            mockMvc.perform(post("/api/experiments/run")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataScale\":50}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/experiments/{batchNo}", "EXP-NOT-RUNNING")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(account.equals("L1001") ? status().isNotFound() : status().isForbidden());
        }
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        for (int scale : List.of(0, 49, 51, 500)) {
            mockMvc.perform(post("/api/experiments/run")
                            .header("Authorization", "Bearer " + administrator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScale", scale))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task WHERE schedule_batch_no LIKE 'EXP-%'",
                Integer.class)).isZero();
    }

    @Test
    void incompleteGeneticRunsExposeNullMeanAndSampleDeviationRatherThanZero() throws Exception {
        for (String algorithm : List.of("FCFS", "GENETIC")) {
            jdbcTemplate.update("""
                    INSERT INTO t_schedule_task
                        (schedule_batch_no, algorithm_type, is_primary, dataset_id,
                         demand_snapshot, resource_snapshot, parameters, random_seed, repeat_index,
                         runtime_environment, status)
                    VALUES ('EXP-PENDING', ?, FALSE, 'EXP-DATA-PENDING',
                            '{"date":"2026-10-26","demands":[]}', '[]', '{}', ?, 0, '{}', 'PENDING')
                    """, algorithm, SEED);
        }
        String administrator = loginAndGetToken("A1001", "Lab@2026");

        JsonNode body = getExperiment(administrator, "EXP-PENDING");

        assertThat(body.path("data").path("status").asText()).isEqualTo("PENDING");
        JsonNode summary = body.path("data").path("geneticSummary");
        for (String metric : List.of("allocationSuccessRate", "averageSeatUtilization",
                "seatPreferenceMismatchRate", "timeFragmentationRate", "seatAllocationFailureRate")) {
            assertThat(summary.path(metric + "Mean").isNull()).isTrue();
            assertThat(summary.path(metric + "StandardDeviation").isNull()).isTrue();
        }
    }

    @Test
    void experimentExecutorHasBoundedQueueAbortPolicyAndGracefulShutdown() {
        assertThat(experimentExecutor.getQueueCapacity()).isEqualTo(4);
        assertThat(experimentExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    void rejectedSubmissionFailsEveryPersistedTaskAndReturnsServiceUnavailable() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("queue full"))
                .when(experimentExecutor).execute(org.mockito.Mockito.any(Runnable.class));
        String administrator = loginAndGetToken("A1001", "Lab@2026");

        MvcResult result = mockMvc.perform(post("/api/experiments/run")
                        .header("Authorization", "Bearer " + administrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataScale\":50,\"seed\":20261024}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(responseJson(result).path("message").asText()).contains("队列");
        String batchNo = jdbcTemplate.queryForObject("""
                SELECT schedule_batch_no FROM t_schedule_task
                WHERE schedule_batch_no LIKE 'EXP-%' ORDER BY task_id DESC LIMIT 1
                """, String.class);
        List<String> statuses = jdbcTemplate.queryForList("""
                SELECT status FROM t_schedule_task WHERE schedule_batch_no = ?
                """, String.class, batchNo);
        assertThat(statuses).hasSize(4).allMatch("FAILED"::equals);
        assertThat(jdbcTemplate.queryForList("""
                SELECT error_message FROM t_schedule_task WHERE schedule_batch_no = ?
                """, String.class, batchNo)).allMatch("EXECUTOR_REJECTED"::equals);
    }

    @Test
    void applicationReadyRecoveryResetsRunningSkipsCompletedAndHonorsBatchLimit() throws Exception {
        Map<String, Object> productionBefore = productionFingerprint();
        insertTask("EXP-RECOVERY-A", "FCFS", "SUCCEEDED", 0, SEED);
        insertTask("EXP-RECOVERY-A", "GREEDY", "RUNNING", 0, SEED);
        insertTask("EXP-RECOVERY-A", "GENETIC", "PENDING", 0, SEED);
        insertTask("EXP-RECOVERY-A", "GENETIC", "PENDING", 1, SEED + 1);
        insertTask("EXP-RECOVERY-B", "FCFS", "PENDING", 0, SEED);
        String reader = loginAndGetToken("L1001", "Lab@2026");
        var recovery = java.util.Arrays.stream(
                        com.campus.lab.experiment.ExperimentService.class.getMethods())
                .filter(method -> method.getName().equals("recoverInterrupted"))
                .findFirst();
        assertThat(recovery).isPresent();
        var listener = recovery.orElseThrow().getAnnotation(
                org.springframework.context.event.EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.value()).contains(
                org.springframework.boot.context.event.ApplicationReadyEvent.class);

        recovery.orElseThrow().invoke(applicationContext.getBean(
                com.campus.lab.experiment.ExperimentService.class));

        awaitCompleted(reader, "EXP-RECOVERY-A");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM t_schedule_task WHERE schedule_batch_no = 'EXP-RECOVERY-B'
                """, String.class)).isEqualTo("PENDING");
        org.mockito.Mockito.verify(algorithmExecutor, org.mockito.Mockito.never()).execute(
                org.mockito.ArgumentMatchers.eq(com.campus.lab.schedule.AlgorithmType.FCFS),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(productionFingerprint()).isEqualTo(productionBefore);
    }

    @Test
    void geneticSummaryIgnoresNullMetricsAndSingleSampleDeviationStaysNull() throws Exception {
        long first = insertTask("EXP-METRIC-NULL", "GENETIC", "SUCCEEDED", 0, SEED);
        insertMetric(first, null);
        long second = insertTask("EXP-METRIC-NULL", "GENETIC", "SUCCEEDED", 1, SEED + 1);
        insertMetric(second, 0.2);
        long single = insertTask("EXP-METRIC-ONE", "GENETIC", "SUCCEEDED", 0, SEED);
        insertMetric(single, 0.1);
        String reader = loginAndGetToken("L1001", "Lab@2026");

        JsonNode nullable = getExperiment(reader, "EXP-METRIC-NULL")
                .path("data").path("geneticSummary");
        assertThat(nullable.path("seatAllocationFailureRateMean").asDouble())
                .isCloseTo(0.2, within(0.000001));
        assertThat(nullable.path("seatAllocationFailureRateStandardDeviation").isNull()).isTrue();
        JsonNode one = getExperiment(reader, "EXP-METRIC-ONE")
                .path("data").path("geneticSummary");
        assertThat(one.path("allocationSuccessRateMean").asDouble())
                .isCloseTo(0.5, within(0.000001));
        assertThat(one.path("allocationSuccessRateStandardDeviation").isNull()).isTrue();
    }

    @Test
    void productionDefaultsRemainTenRepeatsWithPopulationEightyAndTwoHundredGenerations()
            throws Exception {
        var yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> root;
        try (var input = new org.springframework.core.io.ClassPathResource("application.yml")
                .getInputStream()) {
            root = yaml.load(input);
        }
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> experiment = (Map<String, Object>) app.get("experiment");
        assertThat(experiment).isNotNull();
        Map<String, Object> genetic = (Map<String, Object>) experiment.get("genetic");
        assertThat(experiment.get("repeats")).isEqualTo(10);
        assertThat(genetic.get("population-size")).isEqualTo(80);
        assertThat(genetic.get("generations")).isEqualTo(200);
    }

    private void assertSeededBatch(JsonNode body, int scale, long baseSeed) throws Exception {
        assertThat(body.path("data").path("dataScale").asInt()).isEqualTo(scale);
        assertThat(body.path("data").path("seed").asLong()).isEqualTo(baseSeed);
        assertThat(body.path("data").path("datasetId").asText()).startsWith("EXP-DSET-");
        assertThat(body.path("data").path("tasks")).hasSize(4);
        assertThat(body.path("data").path("tasks")).allSatisfy(task -> {
            assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(task.path("primary").asBoolean()).isFalse();
            assertThat(task.path("experiment").isObject()).isTrue();
            for (String metric : List.of("allocationSuccessRate", "averageSeatUtilization",
                    "seatPreferenceMismatchRate", "timeFragmentationRate", "seatAllocationFailureRate")) {
                assertThat(task.path("experiment").path(metric).isNumber()).isTrue();
            }
        });
        List<JsonNode> genetic = new java.util.ArrayList<>();
        body.path("data").path("tasks").forEach(task -> {
            if (task.path("algorithmType").asText().equals("GENETIC")) {
                genetic.add(task);
            } else {
                assertThat(task.path("repeatIndex").asInt()).isZero();
                assertThat(task.path("randomSeed").asLong()).isEqualTo(baseSeed);
            }
        });
        assertThat(genetic).hasSize(2);
        assertThat(genetic).extracting(task -> task.path("repeatIndex").asInt())
                .containsExactly(0, 1);
        assertThat(genetic).extracting(task -> task.path("randomSeed").asLong())
                .containsExactly(baseSeed, baseSeed + 1);
        assertThat(genetic.get(0).path("demandSnapshot"))
                .isEqualTo(genetic.get(1).path("demandSnapshot"));
        assertThat(genetic.get(0).path("resourceSnapshot"))
                .isEqualTo(genetic.get(1).path("resourceSnapshot"));

        JsonNode demands = objectMapper.readTree(snapshot(body, "demand_snapshot")).path("demands");
        assertThat(demands).hasSize(scale);
        int roomCount = 0;
        for (JsonNode demand : demands) {
            if (demand.path("mode").asText().equals("ROOM")) {
                roomCount++;
            }
            assertThat(demand.path("attendeeCount").asInt()).isPositive();
            assertThat(demand.path("durationMinutes").asInt()).isBetween(30, 240);
            assertThat(demand.path("allowedLabIds").isArray()).isTrue();
            assertThat(demand.path("preferredZone").isNull()
                    || demand.path("preferredZone").isTextual()).isTrue();
        }
        assertThat(roomCount).isEqualTo(scale * 3 / 10);
        assertGeneticSummary(body.path("data").path("geneticSummary"), genetic);
    }

    private void assertGeneticSummary(JsonNode summary, List<JsonNode> genetic) {
        for (String metric : List.of("allocationSuccessRate", "averageSeatUtilization",
                "seatPreferenceMismatchRate", "timeFragmentationRate", "seatAllocationFailureRate")) {
            double first = genetic.get(0).path("experiment").path(metric).asDouble();
            double second = genetic.get(1).path("experiment").path(metric).asDouble();
            double mean = (first + second) / 2;
            double deviation = Math.sqrt(((first - mean) * (first - mean)
                    + (second - mean) * (second - mean)) / (genetic.size() - 1));
            assertThat(summary.path(metric + "Mean").asDouble()).isCloseTo(mean, within(0.000001));
            assertThat(summary.path(metric + "StandardDeviation").asDouble())
                    .isCloseTo(deviation, within(0.000001));
        }
    }

    private JsonNode runExperiment(String token, int scale, Long seed) throws Exception {
        String content = seed == null
                ? objectMapper.writeValueAsString(Map.of("dataScale", scale))
                : objectMapper.writeValueAsString(Map.of("dataScale", scale, "seed", seed));
        MvcResult result = mockMvc.perform(post("/api/experiments/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andReturn();
        return responseJson(result).path("data");
    }

    private JsonNode awaitCompleted(String token, String batchNo) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        JsonNode body;
        do {
            body = getExperiment(token, batchNo);
            String status = body.path("data").path("status").asText();
            assertThat(status).isNotEqualTo("FAILED");
            if (status.equals("SUCCEEDED")) {
                return body;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Experiment did not finish: " + body);
    }

    private JsonNode getExperiment(String token, String batchNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/experiments/{batchNo}", batchNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return responseJson(result);
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

    private void insertMetric(long taskId, Double seatFailureRate) {
        jdbcTemplate.update("""
                INSERT INTO t_algorithm_experiment
                    (task_id, algorithm_type, data_scale, allocation_success_rate,
                     average_seat_utilization, seat_preference_mismatch_rate,
                     time_fragmentation_rate, seat_allocation_failure_rate,
                     phase_one_duration_ms, phase_two_duration_ms, total_duration_ms)
                VALUES (?, 'GENETIC', 50, 0.5, 0.5, 0.5, 0.5, ?, 0, 0, 0)
                """, taskId, seatFailureRate);
    }

    private String snapshot(JsonNode body, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM t_schedule_task WHERE schedule_batch_no = ? LIMIT 1",
                String.class, body.path("data").path("batchNo").asText());
    }

    private Map<String, Object> productionFingerprint() {
        return jdbcTemplate.queryForMap("""
                SELECT
                    (SELECT COUNT(*) FROM t_reservation) AS reservation_count,
                    (SELECT COUNT(*) FROM t_reservation_seat) AS assignment_count,
                    (SELECT COUNT(*) FROM t_schedule_result) AS result_count,
                    (SELECT COUNT(*) FROM t_schedule_task
                     WHERE schedule_batch_no NOT LIKE 'EXP-%') AS production_task_count,
                    (SELECT COALESCE(SUM(mask), 0) FROM t_slot_bitmap) AS room_masks,
                    (SELECT COALESCE(SUM(version), 0) FROM t_slot_bitmap) AS room_versions,
                    (SELECT COALESCE(SUM(mask), 0) FROM t_seat_bitmap) AS seat_masks,
                    (SELECT COALESCE(SUM(version), 0) FROM t_seat_bitmap) AS seat_versions
                """);
    }
}
