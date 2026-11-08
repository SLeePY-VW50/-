package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScheduleQueryIntegrationTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate FIRST_DATE = LocalDate.of(2026, 11, 2);
    private static final LocalDate SECOND_DATE = LocalDate.of(2026, 11, 3);

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void adminsCanPageBatchesAndReadThreeTaskMetricsAndResults() throws Exception {
        long labId = createLaboratory(3);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(firstUserId, labId, FIRST_DATE);
        createPending(secondUserId, labId, SECOND_DATE);
        scheduleService.runDueBatches();
        String adminToken = loginAndGetToken("L1001", "Lab@2026");

        JsonNode firstPage = responseJson(mockMvc.perform(get("/api/schedules/batches")
                        .queryParam("page", "0").queryParam("size", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andReturn());
        JsonNode secondPage = responseJson(mockMvc.perform(get("/api/schedules/batches")
                        .queryParam("page", "1").queryParam("size", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn());
        List<String> batchNos = new ArrayList<>();
        batchNos.add(firstPage.path("data").path("items").get(0).path("batchNo").asText());
        batchNos.add(secondPage.path("data").path("items").get(0).path("batchNo").asText());
        assertThat(batchNos).doesNotHaveDuplicates();
        String batchNo = batchNos.getFirst();

        JsonNode detail = responseJson(mockMvc.perform(get("/api/schedules/batches/{batchNo}", batchNo)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchNo").value(batchNo))
                .andExpect(jsonPath("$.data.primaryStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.tasks.length()").value(3))
                .andReturn());
        assertThat(detail.path("data").path("primaryTaskId").asLong()).isPositive();
        assertThat(detail.path("data").path("tasks")).allSatisfy(task -> {
            assertThat(task.path("taskId").asLong()).isPositive();
            assertThat(task.path("datasetId").asText()).isNotBlank();
            assertThat(task.path("experiment").path("allocationSuccessRate").isNumber()).isTrue();
            assertThat(task.path("experiment").path("averageSeatUtilization").isNumber()).isTrue();
            assertThat(task.path("experiment").path("seatPreferenceMismatchRate").isNumber()).isTrue();
            assertThat(task.path("experiment").path("timeFragmentationRate").isNumber()).isTrue();
            assertThat(task.path("experiment").path("seatAllocationFailureRate").isNumber()).isTrue();
            assertThat(task.path("experiment").path("phaseOneDurationMs").isNumber()).isTrue();
            assertThat(task.path("experiment").path("phaseTwoDurationMs").isNumber()).isTrue();
            assertThat(task.path("results")).hasSize(1);
            assertThat(task.path("results").get(0).path("candidateSeatAllocations")).isNotNull();
        });
        String systemAdminToken = loginAndGetToken("A1001", "Lab@2026");
        mockMvc.perform(get("/api/schedules/batches/{batchNo}", batchNo)
                        .header("Authorization", "Bearer " + systemAdminToken))
                .andExpect(status().isOk());
        assertThat(detail.toString()).doesNotContain(
                "password", "openid", "phone", "wechat", "purpose", "demandSnapshot", "resourceSnapshot");
    }

    @Test
    void taskDetailContainsOnlySnapshotMetadataAndRejectsOrdinaryUsers() throws Exception {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(userId, labId, FIRST_DATE);
        scheduleService.runBatch(FIRST_DATE);
        String batchNo = onlyBatchNo();
        long taskId = primaryTaskId(batchNo);
        String adminToken = loginAndGetToken("L1001", "Lab@2026");

        JsonNode detail = responseJson(mockMvc.perform(get("/api/schedules/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.snapshotMetadata.datasetId").isNotEmpty())
                .andExpect(jsonPath("$.data.snapshotMetadata.demandCount").value(1))
                .andExpect(jsonPath("$.data.snapshotMetadata.resourceCount").value(1))
                .andExpect(jsonPath("$.data.parameters.populationSize").value(4))
                .andExpect(jsonPath("$.data.runtimeEnvironment")
                        .value(org.hamcrest.Matchers.instanceOf(java.util.Map.class)))
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andReturn());
        assertThat(detail.toString()).doesNotContain(
                "password", "openid", "phone", "wechat", "purpose", "demandSnapshot", "resourceSnapshot");

        for (String account : new String[] {"20240001", "T1001"}) {
            String token = loginAndGetToken(account, "Lab@2026");
            mockMvc.perform(get("/api/schedules/batches")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
            mockMvc.perform(get("/api/schedules/batches/{batchNo}", batchNo)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/schedules/tasks/{taskId}", taskId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/schedules/batches")
                        .queryParam("size", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
