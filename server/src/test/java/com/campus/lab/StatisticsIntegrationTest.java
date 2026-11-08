package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.test.context.TestPropertySource(
        properties = "app.statistics.jobs.enabled=true")
class StatisticsIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private ApplicationContext applicationContext;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplateSpy;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.campus.lab.statistics.StatisticsRepository statisticsRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private java.time.Clock clock;

    @org.junit.jupiter.api.AfterEach
    void resetStatisticsSpies() {
        org.mockito.Mockito.reset(jdbcTemplateSpy, statisticsRepository, clock);
    }

    @Test
    void dailyStatisticsUseOriginalAssignmentsAndActualCheckinIntervalsAndUpsertIdempotently()
            throws Exception {
        Fixture fixture = fixture(3, "08:00", "12:00");
        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'DISABLED' WHERE seat_id = ?",
                fixture.seatIds().get(2));
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long early = reservation(userId, fixture.labId(), DATE, "08:00", "10:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "提前签退", fixture.seatIds().get(0));
        reservation(userId, fixture.labId(), DATE, "09:00", "10:00", "SEAT",
                "COMPLETED", "09:00", "10:00", "正常到点", fixture.seatIds().get(1));
        reservation(userId, fixture.labId(), DATE, "10:00", "11:00", "ROOM",
                "COMPLETED", "10:00", "10:30", "整室提前签退", null);
        reservation(userId, fixture.labId(), DATE, "11:00", "12:00", "SEAT",
                "NO_SHOW", null, null, "爽约", fixture.seatIds().get(0));
        reservation(userId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "CANCELLED", "08:00", "08:30", "取消不计使用", fixture.seatIds().get(1));
        pending(userId, DATE, "PENDING_SCHEDULE");

        refreshDaily();

        assertDailyMetric(fixture.labId(), 0.75, 0.375, 0.2, 5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?",
                Integer.class, fixture.labId(), DATE)).isOne();
        refreshDaily();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?",
                Integer.class, fixture.labId(), DATE)).isOne();

        jdbcTemplate.update("UPDATE t_reservation SET checkout_time = '2026-10-23 08:30:00' " +
                "WHERE resv_id = ?", early);
        refreshDaily();
        assertDailyMetric(fixture.labId(), 0.75, 0.3125, 0.2, 5);
    }

    @Test
    void heatmapReturnsEverySlotWithMeasuredRoomAndSeatRatiosAndEnforcesRangeAndRoles()
            throws Exception {
        Fixture fixture = fixture(2, "08:00", "12:00");
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, fixture.labId(), DATE, "08:00", "10:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "提前签退", fixture.seatIds().get(0));
        reservation(userId, fixture.labId(), DATE, "09:00", "10:00", "SEAT",
                "COMPLETED", "09:00", "10:00", "正常到点", fixture.seatIds().get(1));
        reservation(userId, fixture.labId(), DATE, "10:00", "11:00", "ROOM",
                "COMPLETED", "10:00", "10:30", "整室", null);
        reservation(userId, fixture.labId(), DATE, "11:00", "12:00", "SEAT",
                "NO_SHOW", null, null, "爽约", fixture.seatIds().get(0));
        String teacher = loginAndGetToken("T1001", "Lab@2026");

        JsonNode body = statisticsJson(teacher, "/api/statistics/heatmap", fixture.labId(),
                DATE, DATE);
        assertThat(body.path("data")).hasSize(1);
        JsonNode day = body.path("data").get(0);
        assertThat(day.path("labId").asLong()).isEqualTo(fixture.labId());
        assertThat(day.path("date").asText()).isEqualTo(DATE.toString());
        assertThat(day.path("slots")).hasSize(48);
        assertSlot(day, 0, "00:00", "00:30", 0, 0);
        assertSlot(day, 16, "08:00", "08:30", 0.5, 0.5);
        assertSlot(day, 18, "09:00", "09:30", 1.0, 0.5);
        assertSlot(day, 20, "10:00", "10:30", 1.0, 1.0);
        assertSlot(day, 22, "11:00", "11:30", 0.5, 0);

        String student = loginAndGetToken("20240001", "Lab@2026");
        mockMvc.perform(get("/api/statistics/heatmap")
                        .queryParam("labId", Long.toString(fixture.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.toString())
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/statistics/heatmap")
                        .queryParam("labId", Long.toString(fixture.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.plusDays(31).toString())
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/statistics/heatmap")
                        .queryParam("labId", Long.toString(fixture.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.minusDays(1).toString())
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardScopesPersonalFactsAndTrendWhileAdminsSeeGlobalCounts() throws Exception {
        Fixture fixture = fixture(2, "08:00", "12:00");
        String ownerAccount = createTestAccount("STUDENT");
        long ownerId = userIdByAccount(ownerAccount);
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 73 WHERE user_id = ?", ownerId);
        long otherId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(ownerId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "本人", fixture.seatIds().get(0));
        pending(ownerId, DATE, "PENDING_SCHEDULE");
        reservation(otherId, fixture.labId(), DATE, "09:00", "10:00", "SEAT",
                "COMPLETED", "09:00", "10:00", "他人", fixture.seatIds().get(0));
        String owner = loginAndGetToken(ownerAccount, "Lab@2026");

        MvcResult personal = mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = responseJson(personal).path("data");
        assertThat(data.path("date").asText()).isEqualTo(DATE.toString());
        assertThat(data.path("labCount").asInt()).isOne();
        assertThat(data.path("reservationCount").asInt()).isEqualTo(2);
        assertThat(data.path("pendingScheduleCount").asInt()).isOne();
        assertThat(data.path("inUseCount").asInt()).isZero();
        assertThat(data.path("plannedUtilization").asDouble()).isCloseTo(0.125, within(0.000001));
        assertThat(data.path("actualUtilization").asDouble()).isCloseTo(0.125, within(0.000001));
        assertThat(data.path("creditScore").asInt()).isEqualTo(73);
        assertThat(data.path("trend")).hasSize(7);
        assertThat(data.path("trend").get(6).path("reservationCount").asInt()).isEqualTo(2);

        String administrator = loginAndGetToken("A1001", "Lab@2026");
        JsonNode global = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + administrator))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(global.path("labCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(global.path("reservationCount").asInt()).isEqualTo(3);
        assertThat(global.path("creditScore").asInt()).isEqualTo(100);

        String emptyAccount = createTestAccount("TEACHER");
        String emptyTeacher = loginAndGetToken(emptyAccount, "Lab@2026");
        JsonNode empty = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + emptyTeacher))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(empty.path("labCount").asInt()).isZero();
        assertThat(empty.path("reservationCount").asInt()).isZero();
        assertThat(empty.path("plannedUtilization").asDouble()).isZero();
        assertThat(empty.path("actualUtilization").asDouble()).isZero();
        assertThat(empty.path("trend")).hasSize(7);
        assertThat(empty.path("trend")).allSatisfy(day -> {
            assertThat(day.path("reservationCount").asInt()).isZero();
            assertThat(day.path("plannedUtilization").asDouble()).isZero();
        });
    }

    @Test
    void csvHasBomRealOwnershipFiltersAndFormulaEscaping() throws Exception {
        Fixture first = fixture(1, "08:00", "12:00");
        Fixture second = fixture(1, "08:00", "12:00");
        String ownerAccount = createTestAccount("STUDENT");
        long ownerId = userIdByAccount(ownerAccount);
        long otherId = userIdByAccount(createTestAccount("STUDENT"));
        long[] owned = new long[4];
        String[] purposes = {"=2+3", "+formula", "-formula", "@formula"};
        for (int index = 0; index < purposes.length; index++) {
            owned[index] = reservation(ownerId, first.labId(), DATE, "08:00", "09:00", "SEAT",
                    "COMPLETED", "08:00", "09:00", purposes[index], first.seatIds().get(0));
        }
        long other = reservation(otherId, first.labId(), DATE, "09:00", "10:00", "SEAT",
                "COMPLETED", "09:00", "10:00", "他人记录", first.seatIds().get(0));
        long otherLab = reservation(ownerId, second.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "其他实验室", second.seatIds().get(0));
        long outside = reservation(ownerId, first.labId(), DATE.minusDays(1), "08:00", "09:00",
                "SEAT", "COMPLETED", "08:00", "09:00", "范围外", first.seatIds().get(0));
        String owner = loginAndGetToken(ownerAccount, "Lab@2026");

        MvcResult result = mockMvc.perform(get("/api/statistics/reservations.csv")
                        .queryParam("labId", Long.toString(first.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.toString())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        for (String purpose : purposes) {
            assertThat(csv).contains("\"'" + purpose + "\"");
        }
        for (long id : owned) {
            assertThat(csv).contains("\"" + id + "\"");
        }
        assertThat(csv).doesNotContain("\"" + other + "\"", "\"" + otherLab + "\"",
                "\"" + outside + "\"");

        String administrator = loginAndGetToken("A1001", "Lab@2026");
        String global = new String(mockMvc.perform(get("/api/statistics/reservations.csv")
                        .queryParam("labId", Long.toString(first.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.toString())
                        .header("Authorization", "Bearer " + administrator))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(global).contains("\"" + other + "\"");
        mockMvc.perform(get("/api/statistics/reservations.csv")
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.plusDays(31).toString())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledAssignedSeatsAndLaterSeatChangesCannotRewriteDailyHistory() throws Exception {
        Fixture fixture = fixture(2, "08:00", "10:00");
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "历史工位", fixture.seatIds().get(0));
        reservation(userId, fixture.labId(), DATE, "09:00", "10:00", "ROOM",
                "COMPLETED", "09:00", "10:00", "历史整室", null);
        refreshDaily(fixture.labId(), DATE);

        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'DISABLED' WHERE seat_id = ?",
                fixture.seatIds().get(0));
        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'MAINTENANCE' WHERE seat_id = ?",
                fixture.seatIds().get(1));
        jdbcTemplate.update("""
                INSERT INTO t_lab_seat
                    (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                VALUES (?, 'LATER', 'ZONE', 3, 'STANDARD', FALSE, 'ACTIVE')
                """, fixture.labId());
        refreshDaily(fixture.labId(), DATE);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 't_stat_daily'
                  AND column_name = 'available_seat_minutes'
                """, Integer.class)).isOne();
        Map<String, Object> historical = jdbcTemplate.queryForMap("""
                SELECT planned_utilization, actual_utilization, available_seat_minutes
                FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, fixture.labId(), DATE);
        assertThat(((Number) historical.get("planned_utilization")).doubleValue())
                .isCloseTo(0.75, within(0.000001));
        assertThat(((Number) historical.get("actual_utilization")).doubleValue())
                .isCloseTo(0.75, within(0.000001));
        assertThat(((Number) historical.get("available_seat_minutes")).longValue()).isEqualTo(240);

        reservation(userId, fixture.labId(), DATE.plusDays(1), "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "未固化停用工位", fixture.seatIds().get(0));
        refreshDaily(fixture.labId(), DATE.plusDays(1));
        Map<String, Object> uncached = jdbcTemplate.queryForMap("""
                SELECT planned_utilization, actual_utilization FROM t_stat_daily
                WHERE lab_id = ? AND stat_date = ?
                """, fixture.labId(), DATE.plusDays(1));
        assertThat(((Number) uncached.get("planned_utilization")).doubleValue())
                .isCloseTo(0.25, within(0.000001));
        assertThat(((Number) uncached.get("actual_utilization")).doubleValue())
                .isCloseTo(0.25, within(0.000001));
    }

    @Test
    void personalDashboardUsesEachDaysOwnLaboratorySetInsteadOfWeekCartesianProduct()
            throws Exception {
        Fixture yesterdayLab = fixture(1, "08:00", "10:00");
        Fixture todayLab = fixture(3, "08:00", "10:00");
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        reservation(userId, yesterdayLab.labId(), TODAY.minusDays(1), "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "昨天A", yesterdayLab.seatIds().get(0));
        reservation(userId, todayLab.labId(), TODAY, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "今天B", todayLab.seatIds().get(0));
        String token = loginAndGetToken(account, "Lab@2026");

        JsonNode data = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", TODAY.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");

        assertThat(data.path("labCount").asInt()).isOne();
        assertThat(data.path("plannedUtilization").asDouble()).isCloseTo(1.0 / 6, within(0.000001));
        assertThat(data.path("actualUtilization").asDouble()).isCloseTo(1.0 / 6, within(0.000001));
        assertThat(data.path("trend").get(5).path("plannedUtilization").asDouble())
                .isCloseTo(0.5, within(0.000001));
        assertThat(data.path("trend").get(6).path("plannedUtilization").asDouble())
                .isCloseTo(1.0 / 6, within(0.000001));
    }

    @Test
    void csvStripsLeadingControlsBeforeFormulaDetectionAndCannotInjectRows() throws Exception {
        Fixture fixture = fixture(1, "08:00", "12:00");
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        List<String> purposes = List.of(
                "\t=2+3", "\r+formula", "\n-formula", "\t\r\n@formula", "普通中文内容");
        for (String purpose : purposes) {
            reservation(userId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                    "COMPLETED", "08:00", "09:00", purpose, fixture.seatIds().get(0));
        }
        String token = loginAndGetToken(account, "Lab@2026");

        byte[] bytes = mockMvc.perform(get("/api/statistics/reservations.csv")
                        .queryParam("labId", Long.toString(fixture.labId()))
                        .queryParam("start", DATE.toString()).queryParam("end", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=2+3\"", "\"'+formula\"", "\"'-formula\"",
                "\"'@formula\"", "\"普通中文内容\"");
        assertThat(csv.replace("\r\n", "")).doesNotContain("\r", "\n", "\t");
    }

    @Test
    void scheduledBatchSharesFactsIsolatesFailureAndBackfillsOnNextRun() throws Exception {
        Fixture failedLab = fixture(1, "08:00", "12:00");
        Fixture followingLab = fixture(1, "08:00", "12:00");
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, failedLab.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "失败后补算", failedLab.seatIds().get(0));
        reservation(userId, followingLab.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "后续实验室", followingLab.seatIds().get(0));
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object firstArgument = invocation.getArgument(1);
            Object labId = firstArgument instanceof Object[] arguments
                    ? arguments[0] : firstArgument;
            if (fail.get() && sql.contains("INSERT INTO t_stat_daily")
                    && labId.equals(failedLab.labId())) {
                throw new org.springframework.dao.DataAccessResourceFailureException("模拟单实验室写失败");
            }
            return invocation.callRealMethod();
        }).when(jdbcTemplateSpy).update(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.any(Object[].class));
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            java.util.List<Object[]> rows = invocation.getArgument(1);
            if (fail.get() && sql.contains("INSERT INTO t_stat_daily")
                    && rows.stream().anyMatch(row -> row[0].equals(failedLab.labId()))) {
                throw new org.springframework.dao.DataAccessResourceFailureException("模拟批量写失败");
            }
            return invocation.callRealMethod();
        }).when(jdbcTemplateSpy).batchUpdate(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList());

        org.assertj.core.api.Assertions.assertThatCode(this::refreshDaily).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, failedLab.labId(), DATE)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, followingLab.labId(), DATE)).isOne();
        org.mockito.Mockito.verify(statisticsRepository)
                .loadFacts(null, null, DATE, DATE);

        fail.set(false);
        org.mockito.Mockito.doReturn(java.time.Instant.parse("2026-10-24T16:00:00Z"))
                .when(clock).instant();
        refreshDaily();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, failedLab.labId(), DATE)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, failedLab.labId(), TODAY)).isOne();
        org.mockito.Mockito.verify(statisticsRepository)
                .loadFacts(null, null, DATE, TODAY);
    }

    @Test
    void scheduledBatchBackfillsInteriorMissingDateBehindLaterHighWater() throws Exception {
        Fixture fixture = fixture(1, "08:00", "12:00");
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "中间缺失补算", fixture.seatIds().get(0));
        refreshDaily();
        org.mockito.Mockito.doReturn(java.time.Instant.parse("2026-10-24T16:00:00Z"))
                .when(clock).instant();
        refreshDaily();
        jdbcTemplate.update("DELETE FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?",
                fixture.labId(), DATE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, fixture.labId(), TODAY)).isOne();

        refreshDaily();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, fixture.labId(), DATE)).isOne();
    }

    @Test
    void dailyBaselineUsesOnlySeatsActiveOrAssignedOnThatDate() {
        Fixture fixture = fixture(2, "08:00", "10:00");
        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'DISABLED' WHERE seat_id = ?",
                fixture.seatIds().get(1));
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "D1停用工位", fixture.seatIds().get(1));
        var service = applicationContext.getBean(com.campus.lab.statistics.StatisticsService.class);

        service.refreshDailyBatch(List.of(
                new com.campus.lab.statistics.StatisticsViews.LabDateTarget(fixture.labId(), DATE),
                new com.campus.lab.statistics.StatisticsViews.LabDateTarget(
                        fixture.labId(), DATE.plusDays(1))));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT available_seat_minutes FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Long.class, fixture.labId(), DATE)).isEqualTo(240);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT available_seat_minutes FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Long.class, fixture.labId(), DATE.plusDays(1))).isEqualTo(120);
    }

    @Test
    void scheduledBatchBackfillsZeroReservationGapBetweenActivityDates() throws Exception {
        Fixture fixture = fixture(1, "08:00", "10:00");
        LocalDate thirdDay = TODAY.plusDays(1);
        for (LocalDate date : List.of(DATE, thirdDay)) {
            jdbcTemplate.update("""
                    INSERT INTO t_stat_daily
                        (lab_id, stat_date, planned_utilization, actual_utilization,
                         no_show_rate, reservation_count, available_seat_minutes)
                    VALUES (?, ?, 0, 0, 0, 0, 120)
                    """, fixture.labId(), date);
        }
        org.mockito.Mockito.doReturn(java.time.Instant.parse("2026-10-25T16:00:00Z"))
                .when(clock).instant();

        refreshDaily();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, fixture.labId(), TODAY)).isOne();
        Map<String, Object> gap = jdbcTemplate.queryForMap("""
                SELECT reservation_count, planned_utilization, actual_utilization,
                       available_seat_minutes
                FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, fixture.labId(), TODAY);
        assertThat(((Number) gap.get("reservation_count")).intValue()).isZero();
        assertThat(((Number) gap.get("planned_utilization")).doubleValue()).isZero();
        assertThat(((Number) gap.get("actual_utilization")).doubleValue()).isZero();
        assertThat(((Number) gap.get("available_seat_minutes")).longValue()).isEqualTo(120);
    }

    @Test
    void multiYearBackfillLoadsFactsInBoundedThirtyOneDayChunks() throws Exception {
        Fixture fixture = fixture(1, "08:00", "10:00");
        LocalDate oldActivity = LocalDate.of(2020, 1, 1);
        jdbcTemplate.update("""
                INSERT INTO t_stat_daily
                    (lab_id, stat_date, planned_utilization, actual_utilization,
                     no_show_rate, reservation_count, available_seat_minutes)
                VALUES (?, ?, 0, 0, 0, 0, 120)
                """, fixture.labId(), oldActivity);

        refreshDaily();

        List<LocalDate[]> ranges = org.mockito.Mockito.mockingDetails(statisticsRepository)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("loadFacts"))
                .map(invocation -> new LocalDate[] {
                        invocation.getArgument(2), invocation.getArgument(3)})
                .toList();
        assertThat(ranges).isNotEmpty().allSatisfy(range ->
                assertThat(java.time.temporal.ChronoUnit.DAYS.between(range[0], range[1]))
                        .isLessThan(31));
    }

    @Test
    void personalDashboardBaselineIncludesOtherUsersAssignedSeatsBeforeAndAfterRefresh()
            throws Exception {
        Fixture fixture = fixture(2, "08:00", "10:00");
        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'DISABLED' WHERE seat_id = ?",
                fixture.seatIds().get(1));
        String ownerAccount = createTestAccount("STUDENT");
        long ownerId = userIdByAccount(ownerAccount);
        long otherId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(otherId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "他人停用工位", fixture.seatIds().get(1));
        reservation(ownerId, fixture.labId(), DATE, "08:00", "09:00", "SEAT",
                "COMPLETED", "08:00", "09:00", "本人正常使用", fixture.seatIds().get(0));
        String token = loginAndGetToken(ownerAccount, "Lab@2026");

        JsonNode before = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(before.path("plannedUtilization").asDouble()).isCloseTo(0.25, within(0.000001));
        assertThat(before.path("actualUtilization").asDouble()).isCloseTo(0.25, within(0.000001));
        assertThat(before.path("labCount").asInt()).isOne();

        refreshDaily(fixture.labId(), DATE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT available_seat_minutes FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Long.class, fixture.labId(), DATE)).isEqualTo(240);
        JsonNode after = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(after.path("plannedUtilization").asDouble()).isCloseTo(0.25, within(0.000001));
        assertThat(after.path("actualUtilization").asDouble()).isCloseTo(0.25, within(0.000001));
    }

    @Test
    void currentTimeCapsCheckedInUsageAndDailyJobsHaveExpectedSchedule() throws Exception {
        Fixture fixture = fixture(1, "06:00", "12:00");
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        reservation(userId, fixture.labId(), TODAY, "06:00", "10:00", "SEAT",
                "IN_USE", "07:45", null, "当前仍在使用", fixture.seatIds().get(0));

        refreshDaily();
        refreshDaily(fixture.labId(), TODAY);
        JsonNode dashboard = responseJson(mockMvc.perform(get("/api/statistics/dashboard")
                        .header("Authorization", "Bearer " + loginAndGetToken("A1001", "Lab@2026")))
                .andExpect(status().isOk()).andReturn());
        assertThat(dashboard.path("data").path("inUseCount").asInt()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT actual_utilization FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Double.class, fixture.labId(), TODAY.minusDays(1))).isZero();
        Map<String, Object> current = jdbcTemplate.queryForMap("""
                SELECT planned_utilization, actual_utilization FROM t_stat_daily
                WHERE lab_id = ? AND stat_date = ?
                """, fixture.labId(), TODAY);
        assertThat(((Number) current.get("planned_utilization")).doubleValue())
                .isCloseTo(2.0 / 3, within(0.000001));
        assertThat(((Number) current.get("actual_utilization")).doubleValue())
                .isCloseTo(15.0 / 360, within(0.000001));

        Object jobs = applicationContext.getBean("statisticsScheduledJobs");
        var scheduled = jobs.getClass().getMethod("refreshDaily")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 5 0 * * *");
    }

    private void assertDailyMetric(long labId, double planned, double actual,
            double noShowRate, int reservationCount) {
        JsonNode metric = objectMapper.valueToTree(jdbcTemplate.queryForMap("""
                SELECT planned_utilization, actual_utilization, no_show_rate, reservation_count
                FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, labId, DATE));
        assertThat(metric.path("planned_utilization").asDouble()).isCloseTo(planned, within(0.000001));
        assertThat(metric.path("actual_utilization").asDouble()).isCloseTo(actual, within(0.000001));
        assertThat(metric.path("no_show_rate").asDouble()).isCloseTo(noShowRate, within(0.000001));
        assertThat(metric.path("reservation_count").asInt()).isEqualTo(reservationCount);
    }

    private void assertSlot(JsonNode day, int slot, String start, String end,
            double planned, double actual) {
        JsonNode value = day.path("slots").get(slot);
        assertThat(value.path("slot").asInt()).isEqualTo(slot);
        assertThat(value.path("start").asText()).isEqualTo(start);
        assertThat(value.path("end").asText()).isEqualTo(end);
        assertThat(value.path("plannedRatio").asDouble()).isCloseTo(planned, within(0.000001));
        assertThat(value.path("actualRatio").asDouble()).isCloseTo(actual, within(0.000001));
    }

    private JsonNode statisticsJson(String token, String path, long labId,
            LocalDate start, LocalDate end) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .queryParam("labId", Long.toString(labId))
                        .queryParam("start", start.toString()).queryParam("end", end.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return responseJson(result);
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void refreshDaily() throws Exception {
        assertThat(applicationContext.containsBean("statisticsScheduledJobs")).isTrue();
        Object jobs = applicationContext.getBean("statisticsScheduledJobs");
        jobs.getClass().getMethod("refreshDaily").invoke(jobs);
    }

    private void refreshDaily(long labId, LocalDate date) throws Exception {
        Object service = applicationContext.getBean("statisticsService");
        service.getClass().getMethod("refreshDaily", long.class, LocalDate.class)
                .invoke(service, labId, date);
    }

    private Fixture fixture(int seatCount, String open, String close) {
        String code = "STAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                INSERT INTO t_lab (lab_code, name, location, capacity, status)
                VALUES (?, '统计测试实验室', '测试位置', ?, 'ACTIVE')
                """, code, seatCount);
        long labId = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        List<Long> seatIds = new java.util.ArrayList<>();
        for (int index = 1; index <= seatCount; index++) {
            jdbcTemplate.update("""
                    INSERT INTO t_lab_seat
                        (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                    VALUES (?, ?, 'ZONE', ?, 'STANDARD', FALSE, 'ACTIVE')
                    """, labId, "S" + index, index);
            seatIds.add(jdbcTemplate.queryForObject(
                    "SELECT seat_id FROM t_lab_seat WHERE lab_id = ? AND seat_index = ?",
                    Long.class, labId, index));
        }
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO t_time_rule (lab_id, day_of_week, open_time, close_time)
                    VALUES (?, ?, ?, ?)
                    """, labId, day, open + ":00", close + ":00");
        }
        return new Fixture(labId, List.copyOf(seatIds));
    }

    private long reservation(long userId, long labId, LocalDate date, String start, String end,
            String mode, String status, String checkin, String checkout, String purpose, Long seatId) {
        LocalDateTime startTime = date.atTime(java.time.LocalTime.parse(start));
        LocalDateTime endTime = date.atTime(java.time.LocalTime.parse(end));
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose,
                     checkin_time, checkout_time, status)
                VALUES (?, 'FIXED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, mode, date, start + ":00", end + ":00",
                java.time.Duration.between(startTime, endTime).toMinutes(), labId, startTime, endTime,
                mode.equals("ROOM") ? 2 : 1, mode.equals("SEAT") ? 1 : null, purpose,
                checkin == null ? null : date.atTime(java.time.LocalTime.parse(checkin)),
                checkout == null ? null : date.atTime(java.time.LocalTime.parse(checkout)), status);
        long reservationId = jdbcTemplate.queryForObject(
                "SELECT MAX(resv_id) FROM t_reservation WHERE user_id = ?", Long.class, userId);
        if (seatId != null) {
            int startSlot = java.time.LocalTime.parse(start).getHour() * 2;
            int endSlot = java.time.LocalTime.parse(end).getHour() * 2;
            long mask = ((1L << (endSlot - startSlot)) - 1) << startSlot;
            jdbcTemplate.update("""
                    INSERT INTO t_reservation_seat
                        (resv_id, seat_id, time_mask, assignment_method)
                    VALUES (?, ?, ?, 'FIXED')
                    """, reservationId, seatId, mask);
        }
        return reservationId;
    }

    private void pending(long userId, LocalDate date, String status) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     attendee_count, seat_count, purpose, status)
                VALUES (?, 'SCHEDULABLE', 'SEAT', ?, '08:00:00', '09:00:00', 60,
                        1, 1, '待排期', ?)
                """, userId, date, status);
    }

    private record Fixture(long labId, List<Long> seatIds) {
    }
}
