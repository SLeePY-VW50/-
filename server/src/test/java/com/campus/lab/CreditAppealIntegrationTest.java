package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.credit.AppealRepository;
import com.campus.lab.credit.CreditService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

class CreditAppealIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate FUTURE_DATE = LocalDate.of(2026, 11, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private CreditService creditService;

    @MockitoSpyBean
    private JdbcTemplate spyJdbcTemplate;

    @MockitoSpyBean
    private AppealRepository appealRepository;

    @AfterEach
    void resetSpy() {
        Mockito.reset(spyJdbcTemplate, appealRepository);
    }

    @Test
    void creditAndViolationQueriesAreScopedToOwnerAndPaged() throws Exception {
        Fixture owner = fixture("STUDENT");
        Fixture other = fixture("STUDENT");
        long labId = createLaboratory(4);
        long firstReservation = createLateViolation(owner, labId);
        long secondReservation = createLateViolation(owner, createLaboratory(4));

        mockMvc.perform(get("/api/credit/me")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditScore").value(80))
                .andExpect(jsonPath("$.data.records.page").value(0))
                .andExpect(jsonPath("$.data.records.size").value(1))
                .andExpect(jsonPath("$.data.records.total").value(2))
                .andExpect(jsonPath("$.data.records.items.length()").value(1))
                .andExpect(jsonPath("$.data.records.items[0].changeType").value("DEDUCT"))
                .andExpect(jsonPath("$.data.records.items[0].balanceBefore").value(90))
                .andExpect(jsonPath("$.data.records.items[0].balanceAfter").value(80));
        mockMvc.perform(get("/api/credit/me")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.items[0].balanceBefore").value(100))
                .andExpect(jsonPath("$.data.records.items[0].balanceAfter").value(90));

        long secondViolation = violationId(secondReservation);
        mockMvc.perform(get("/api/credit/violations")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].violationId").value(secondViolation))
                .andExpect(jsonPath("$.data.items[0].resvId").value(secondReservation))
                .andExpect(jsonPath("$.data.items[0].appealStatus").doesNotExist());
        long appealId = createAppeal(owner.token(), secondViolation, "查询申诉状态");
        mockMvc.perform(get("/api/credit/violations")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].appealId").value(appealId))
                .andExpect(jsonPath("$.data.items[0].appealStatus").value("PENDING"));

        mockMvc.perform(get("/api/credit/me")
                        .header("Authorization", "Bearer " + other.token())
                        .param("userId", Long.toString(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditScore").value(100))
                .andExpect(jsonPath("$.data.records.total").value(0));
        mockMvc.perform(get("/api/credit/violations")
                        .header("Authorization", "Bearer " + other.token())
                        .param("userId", Long.toString(owner.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(firstReservation).isPositive();
    }

    @Test
    void otherUserCannotAppealAndDuplicateAppealIsConflict() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        Fixture other = fixture("STUDENT");

        appeal(other.token(), fixture.violationId(), "越权申诉")
                .andExpect(status().isNotFound());
        createAppeal(fixture.owner().token(), fixture.violationId(), "本人首次申诉");
        appeal(fixture.owner().token(), fixture.violationId(), "重复申诉")
                .andExpect(status().isConflict());

        assertThat(appealCount(fixture.violationId())).isEqualTo(1);
    }

    @Test
    void approvalRestoresActualFivePointsAndPreservesDeductionAndRestore() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 5);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "请求撤销迟到取消");
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        assertThat(creditScore(fixture.owner().userId())).isZero();

        review(administrator, appealId, "APPROVED", "情况属实")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewOpinion").value("情况属实"));

        assertThat(violationStatus(fixture.violationId())).isEqualTo("REVOKED");
        assertThat(creditScore(fixture.owner().userId())).isEqualTo(5);
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(2);
        Map<String, Object> deduction = creditRecord(fixture.owner().userId(), "DEDUCT");
        assertThat(deduction.get("change_amount")).isEqualTo(-5);
        assertThat(deduction.get("balance_before")).isEqualTo(5);
        assertThat(deduction.get("balance_after")).isEqualTo(0);
        Map<String, Object> restore = creditRecord(fixture.owner().userId(), "RESTORE");
        assertThat(((Number) restore.get("violation_id")).longValue()).isEqualTo(fixture.violationId());
        assertThat(restore.get("change_amount")).isEqualTo(5);
        assertThat(restore.get("balance_before")).isEqualTo(0);
        assertThat(restore.get("balance_after")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_violation WHERE violation_id = ? AND revoked_at IS NOT NULL",
                Integer.class, fixture.violationId())).isEqualTo(1);

        review(administrator, appealId, "APPROVED", "情况属实")
                .andExpect(status().isOk());
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(2);
        assertThat(creditScore(fixture.owner().userId())).isEqualTo(5);
        review(administrator, appealId, "APPROVED", "改变核定意见")
                .andExpect(status().isConflict());
        review(administrator, appealId, "REJECTED", "改变核定结果")
                .andExpect(status().isConflict());
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(2);
    }

    @Test
    void rejectedReviewKeepsViolationActiveAndDoesNotRestore() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "请求复核");
        String administrator = loginAndGetToken("A1001", "Lab@2026");

        review(administrator, appealId, "REJECTED", "证据不足")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(violationStatus(fixture.violationId())).isEqualTo("ACTIVE");
        assertThat(creditScore(fixture.owner().userId())).isEqualTo(90);
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(1);
        review(administrator, appealId, "REJECTED", "证据不足")
                .andExpect(status().isOk());
        review(administrator, appealId, "APPROVED", "核定后变更")
                .andExpect(status().isConflict());
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(1);
    }

    @Test
    void twoConcurrentApprovalsRestoreAtMostOnce() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "并发核定");
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        CountDownLatch firstLockHeld = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch secondLockQueryEntered = new CountDownLatch(1);
        AtomicInteger lockCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (lockCalls.incrementAndGet() == 1) {
                Object result = invocation.callRealMethod();
                firstLockHeld.countDown();
                assertThat(allowFirstToFinish.await(30, TimeUnit.SECONDS)).isTrue();
                return result;
            }
            secondLockQueryEntered.countDown();
            return invocation.callRealMethod();
        }).when(appealRepository).lockAppeal(appealId);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Integer> first = executor.submit(() -> review(
                    administrator, appealId, "APPROVED", "并发相同核定")
                    .andReturn().getResponse().getStatus());
            assertThat(firstLockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> review(
                    administrator, appealId, "APPROVED", "并发相同核定")
                    .andReturn().getResponse().getStatus());
            assertThat(secondLockQueryEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();

            allowFirstToFinish.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            allowFirstToFinish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(creditScore(fixture.owner().userId())).isEqualTo(100);
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_credit_record
                WHERE violation_id = ? AND change_type = 'RESTORE'
                """, Integer.class, fixture.violationId())).isEqualTo(1);
        assertThat(violationStatus(fixture.violationId())).isEqualTo("REVOKED");
    }

    @Test
    void directConcurrentRestoreIsIdempotent() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long administratorId = userIdByAccount("A1001");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreditService.CreditAdjustment>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return creditService.restore(fixture.violationId(), administratorId);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CreditService.CreditAdjustment> adjustments = new ArrayList<>();
            for (Future<CreditService.CreditAdjustment> future : futures) {
                adjustments.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(adjustments).extracting(CreditService.CreditAdjustment::actualPoints)
                    .containsExactlyInAnyOrder(10, 0);
            assertThat(adjustments).filteredOn(adjustment -> adjustment.actualPoints() == 0)
                    .singleElement()
                    .satisfies(adjustment -> {
                        assertThat(adjustment.balanceBefore()).isEqualTo(100);
                        assertThat(adjustment.balanceAfter()).isEqualTo(100);
                    });
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(creditScore(fixture.owner().userId())).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_credit_record
                WHERE violation_id = ? AND change_type = 'RESTORE'
                """, Integer.class, fixture.violationId())).isEqualTo(1);
    }

    @Test
    void restoreIsCappedAtOneHundredAndLedgerUsesActualAmount() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "上界恢复");
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 95 WHERE user_id = ?", fixture.owner().userId());
        String administrator = loginAndGetToken("A1001", "Lab@2026");

        review(administrator, appealId, "APPROVED", "核定通过").andExpect(status().isOk());

        assertThat(creditScore(fixture.owner().userId())).isEqualTo(100);
        Map<String, Object> restore = creditRecord(fixture.owner().userId(), "RESTORE");
        assertThat(restore.get("change_amount")).isEqualTo(5);
        assertThat(restore.get("balance_before")).isEqualTo(95);
        assertThat(restore.get("balance_after")).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deduct_points FROM t_violation WHERE violation_id = ?",
                Integer.class, fixture.violationId())).isEqualTo(10);
    }

    @Test
    void revokedViolationCannotBeAppealedAgain() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "首次申诉");
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        review(administrator, appealId, "APPROVED", "撤销违规").andExpect(status().isOk());

        appeal(fixture.owner().token(), fixture.violationId(), "已撤销违规再次申诉")
                .andExpect(status().isConflict());
        assertThat(appealCount(fixture.violationId())).isEqualTo(1);
    }

    @Test
    void appealAndReviewInputsAreValidated() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "有效申诉");
        String administrator = loginAndGetToken("A1001", "Lab@2026");

        appeal(fixture.owner().token(), fixture.violationId(), " ")
                .andExpect(status().isBadRequest());
        appeal(fixture.owner().token(), fixture.violationId(), "x".repeat(1001))
                .andExpect(status().isBadRequest());
        review(administrator, appealId, "APPROVED", " ")
                .andExpect(status().isBadRequest());
        review(administrator, appealId, "INVALID", "无效决定")
                .andExpect(status().isBadRequest());

        assertThat(violationStatus(fixture.violationId())).isEqualTo("ACTIVE");
        assertThat(creditScore(fixture.owner().userId())).isEqualTo(90);
    }

    @Test
    void adminAppealListIsPagedAndFilteredAndLabAdminCannotReview() throws Exception {
        ViolationFixture first = violationFixture("STUDENT", 100);
        ViolationFixture second = violationFixture("STUDENT", 100);
        long firstAppeal = createAppeal(first.owner().token(), first.violationId(), "第一条申诉");
        long secondAppeal = createAppeal(second.owner().token(), second.violationId(), "第二条申诉");
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        String labAdministrator = loginAndGetToken("L1001", "Lab@2026");

        mockMvc.perform(get("/api/admin/appeals")
                        .header("Authorization", "Bearer " + administrator)
                        .param("status", "PENDING").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"));
        review(labAdministrator, firstAppeal, "APPROVED", "越权核定")
                .andExpect(status().isForbidden());
        review(administrator, firstAppeal, "REJECTED", "驳回第一条")
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/appeals")
                        .header("Authorization", "Bearer " + administrator)
                        .param("status", "PENDING").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].appealId").value(secondAppeal));
        mockMvc.perform(get("/api/admin/appeals")
                        .header("Authorization", "Bearer " + labAdministrator))
                .andExpect(status().isForbidden());
    }

    @Test
    void personalCreditEndpointsRejectAdministratorRoles() throws Exception {
        for (String account : new String[] {"L1001", "A1001"}) {
            String token = loginAndGetToken(account, "Lab@2026");
            mockMvc.perform(get("/api/credit/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/credit/violations").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void restoreFailureRollsBackAppealViolationAndCredit() throws Exception {
        ViolationFixture fixture = violationFixture("STUDENT", 100);
        long appealId = createAppeal(fixture.owner().token(), fixture.violationId(), "恢复故障");
        String administrator = loginAndGetToken("A1001", "Lab@2026");
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO t_credit_record")) {
                throw new IllegalStateException("注入恢复流水失败");
            }
            return invocation.callRealMethod();
        }).when(spyJdbcTemplate).update(anyString(), any(Object[].class));

        review(administrator, appealId, "APPROVED", "核定通过")
                .andExpect(status().isInternalServerError());
        Mockito.reset(spyJdbcTemplate);

        assertThat(appealStatus(appealId)).isEqualTo("PENDING");
        assertThat(violationStatus(fixture.violationId())).isEqualTo("ACTIVE");
        assertThat(creditScore(fixture.owner().userId())).isEqualTo(90);
        assertThat(creditRecordCount(fixture.owner().userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_appeal WHERE appeal_id = ? AND reviewed_at IS NULL",
                Integer.class, appealId)).isEqualTo(1);
    }

    private ViolationFixture violationFixture(String role, int initialCredit) throws Exception {
        Fixture owner = fixture(role);
        long labId = createLaboratory(4);
        long reservationId = createLateViolation(owner, labId, initialCredit);
        return new ViolationFixture(owner, labId, reservationId, violationId(reservationId));
    }

    private long createLateViolation(Fixture owner, long labId) throws Exception {
        return createLateViolation(owner, labId, null);
    }

    private long createLateViolation(Fixture owner, long labId, Integer creditBeforeCancel) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", FUTURE_DATE.toString());
        request.put("startTime", "09:00");
        request.put("endTime", "10:00");
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", "信用申诉集成测试");
        request.put("preferences", Map.of());
        JsonNode created = responseJson(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        long reservationId = created.path("data").path("resvId").asLong();
        jdbcTemplate.update("""
                UPDATE t_reservation
                SET request_date = ?, start_time = TIMESTAMP(?, TIME(start_time)),
                    end_time = TIMESTAMP(?, TIME(end_time))
                WHERE resv_id = ?
                """, TODAY, TODAY, TODAY, reservationId);
        jdbcTemplate.update("UPDATE t_slot_bitmap SET bitmap_date = ? WHERE lab_id = ? AND bitmap_date = ?",
                TODAY, labId, FUTURE_DATE);
        jdbcTemplate.update("""
                UPDATE t_seat_bitmap b JOIN t_lab_seat s ON s.seat_id = b.seat_id
                SET b.bitmap_date = ? WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, TODAY, labId, FUTURE_DATE);
        if (creditBeforeCancel != null) {
            jdbcTemplate.update("UPDATE sys_user SET credit_score = ? WHERE user_id = ?",
                    creditBeforeCancel, owner.userId());
        }
        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk());
        return reservationId;
    }

    private Fixture fixture(String role) throws Exception {
        String account = createTestAccount(role);
        return new Fixture(userIdByAccount(account), loginAndGetToken(account, "Lab@2026"));
    }

    private ResultActions appeal(String token, long violationId, String reason) throws Exception {
        return mockMvc.perform(post("/api/appeals")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "violationId", violationId, "reason", reason))));
    }

    private long createAppeal(String token, long violationId, String reason) throws Exception {
        JsonNode response = responseJson(appeal(token, violationId, reason)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        return response.path("data").path("appealId").asLong();
    }

    private ResultActions review(String token, long appealId, String decision, String opinion) throws Exception {
        return mockMvc.perform(post("/api/admin/appeals/{id}/review", appealId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "decision", decision, "opinion", opinion))));
    }

    private long violationId(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT violation_id FROM t_violation WHERE resv_id = ?", Long.class, reservationId);
    }

    private String violationStatus(long violationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_violation WHERE violation_id = ?", String.class, violationId);
    }

    private String appealStatus(long appealId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_appeal WHERE appeal_id = ?", String.class, appealId);
    }

    private int appealCount(long violationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_appeal WHERE violation_id = ?", Integer.class, violationId);
    }

    private int creditScore(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
    }

    private int creditRecordCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_credit_record WHERE user_id = ?", Integer.class, userId);
    }

    private Map<String, Object> creditRecord(long userId, String changeType) {
        return jdbcTemplate.queryForMap("""
                SELECT violation_id, change_amount, balance_before, balance_after
                FROM t_credit_record WHERE user_id = ? AND change_type = ?
                """, userId, changeType);
    }

    private record Fixture(long userId, String token) {
    }

    private record ViolationFixture(Fixture owner, long labId, long reservationId, long violationId) {
    }
}
