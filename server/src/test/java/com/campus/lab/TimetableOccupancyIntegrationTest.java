package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.campus.lab.checkin.qr.CheckinQrService;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationScheduledJobs;
import com.campus.lab.reservation.ReservationService;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class TimetableOccupancyIntegrationTest extends AbstractCheckinNonTransactionalTest {

    private static final LocalDate WEEK1 = LocalDate.of(2026, 10, 19);
    private static final LocalDate INSIDE_WINDOW = LocalDate.of(2026, 10, 31);
    private static final LocalDate LEFTOVER = LocalDate.of(2026, 11, 7);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private CheckinQrService qrService;

    @Autowired
    private ReservationScheduledJobs jobs;

    @MockitoSpyBean
    private Clock clock;

    private long labId;
    private String labCode;
    private String semesterCode;
    private long semesterId;
    private String sysToken;
    private String adminToken;
    private String teacherAccount;
    private String studentA;
    private String studentB;
    private String outsider;

    @BeforeEach
    void fixture() throws Exception {
        labId = createLaboratory(4);
        labCode = jdbcTemplate.queryForObject(
                "SELECT lab_code FROM t_lab WHERE lab_id = ?", String.class, labId);
        String sysAccount = createTestAccount("SYS_ADMIN");
        String adminAccount = createTestAccount("LAB_ADMIN");
        sysToken = loginAndGetToken(sysAccount, USER_PASSWORD);
        adminToken = loginAndGetToken(adminAccount, USER_PASSWORD);
        teacherAccount = createTestAccount("TEACHER");
        studentA = createTestAccount("STUDENT");
        studentB = createTestAccount("STUDENT");
        outsider = createTestAccount("STUDENT");
        semesterCode = "TT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MvcResult created = send(sysToken, "/api/timetable/semesters", Map.of(
                "code", semesterCode,
                "name", "课表占用测试学期",
                "week1Monday", WEEK1.toString(),
                "weekCount", 18));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        semesterId = responseJson(created).path("data").path("semesterId").asLong();
        jdbcTemplate.update("INSERT INTO t_lab_admin (lab_id, user_id) VALUES (?, ?)",
                labId, userIdByAccount(adminAccount));
    }

    @AfterEach
    void resetClock() {
        Mockito.reset(clock);
    }

    @Test
    void labAdminCannotDefineTheSemesterAndWritesOnlyAdministeredLabs() throws Exception {
        assertThat(send(adminToken, "/api/timetable/semesters", Map.of(
                "code", semesterCode + "X",
                "name", "越权学期",
                "week1Monday", WEEK1.toString(),
                "weekCount", 18)).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(sysToken, "/api/timetable/semesters", Map.of(
                "code", semesterCode + "Y",
                "name", "非周一",
                "week1Monday", "2026-10-20",
                "weekCount", 18)).getResponse().getStatus()).isEqualTo(400);

        long otherLabId = createLaboratory(2);
        MvcResult denied = send(adminToken, "/api/timetable/entries", entry(otherLabId, 1, 2, 1, 1, List.of(studentA)));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        String studentToken = loginAndGetToken(studentA, USER_PASSWORD);
        assertThat(send(studentToken, "/api/timetable/entries", entry(labId, 1, 2, 1, 1, List.of(studentA)))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void smallClassOccupiesTheWholeRoomOutsideThePersonalWindowAndListsConflicts() throws Exception {
        reservationService.create(userIdByAccount(studentA), new CreateReservationRequest(
                AllocationMode.FIXED, labId, INSIDE_WINDOW, "08:30", "10:00",
                null, null, null, ReservationMode.SEAT, 1, 1,
                "个人占座", ReservationPreferences.empty()));

        MvcResult conflicted = send(adminToken, "/api/timetable/entries",
                entry(labId, 1, 2, 1, 16, List.of(studentA)));
        assertThat(conflicted.getResponse().getStatus()).isEqualTo(409);
        JsonNode conflicts = responseJson(conflicted).path("data").path("conflicts");
        assertThat(conflicts.isArray()).isTrue();
        assertThat(conflicts).anySatisfy(item -> {
            assertThat(item.path("date").asText()).isEqualTo(INSIDE_WINDOW.toString());
            assertThat(item.path("account").asText()).isEqualTo(studentA);
            assertThat(item.path("userName").asText()).isNotBlank();
            assertThat(item.path("reservationId").asLong()).isPositive();
        });
        assertThat(entryCount()).isZero();
        assertThat(roomMask(INSIDE_WINDOW)).isZero();

        MvcResult created = send(adminToken, "/api/timetable/entries",
                entry(labId, 3, 4, 1, 16, List.of(studentA, studentB)));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        JsonNode entry = responseJson(created).path("data");
        long entryId = entry.path("entryId").asLong();
        assertThat(entry.path("sessionCount").asInt()).isEqualTo(16);
        assertThat(entry.path("roster")).hasSize(2);
        long courseMask = TimeSlotMask.of(2, 4).value();
        assertThat(roomMask(LocalDate.of(2026, 10, 24))).isEqualTo(courseMask);
        assertThat(seatMaskSum(LocalDate.of(2026, 10, 24))).isZero();
        assertThat(roomMask(LocalDate.of(2027, 2, 6))).isEqualTo(courseMask);
        Map<String, Object> sample = jdbcTemplate.queryForMap("""
                SELECT r.reservation_mode, r.occupancy_source, r.attendee_count, r.seat_count, r.user_id
                FROM t_reservation r
                JOIN t_timetable_session s ON s.resv_id = r.resv_id
                WHERE s.entry_id = ? AND s.session_date = '2026-10-24'
                """, entryId);
        assertThat(sample.get("reservation_mode")).isEqualTo("ROOM");
        assertThat(sample.get("occupancy_source")).isEqualTo("TIMETABLE");
        assertThat(((Number) sample.get("attendee_count")).intValue()).isEqualTo(2);
        assertThat(((Number) sample.get("seat_count")).intValue()).isEqualTo(4);
        assertThat(((Number) sample.get("user_id")).longValue()).isEqualTo(userIdByAccount(teacherAccount));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_timetable_session s ON s.resv_id = rs.resv_id
                WHERE s.entry_id = ? AND s.session_date = '2026-10-24'
                """, Integer.class, entryId)).isEqualTo(4);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reservationService.create(
                userIdByAccount(outsider), new CreateReservationRequest(
                        AllocationMode.FIXED, labId, INSIDE_WINDOW, "10:05", "11:45",
                        null, null, null, ReservationMode.SEAT, 1, 1,
                        "再占课表节次", ReservationPreferences.empty())))
                .hasMessageContaining("冲突");
        reservationService.create(userIdByAccount(outsider), new CreateReservationRequest(
                AllocationMode.FIXED, labId, LEFTOVER, "08:30", "10:00",
                null, null, null, ReservationMode.SEAT, 1, 1,
                "课表以外的空闲节", ReservationPreferences.empty()));

        MvcResult added = send(adminToken, "/api/timetable/entries/" + entryId + "/roster",
                Map.of("studentAccounts", List.of(outsider)));
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(added).path("data").path("roster")).hasSize(3);
        long removedId = userIdByAccount(studentB);
        MvcResult removed = mockMvc.perform(delete(
                        "/api/timetable/entries/{entryId}/roster/{studentUserId}", entryId, removedId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(removed.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(removed).path("data").path("roster")).hasSize(2);

        long reservationId = jdbcTemplate.queryForObject("""
                SELECT resv_id FROM t_timetable_session
                WHERE entry_id = ? AND session_date = '2026-10-24'
                """, Long.class, entryId);
        jdbcTemplate.update("""
                UPDATE t_reservation
                SET start_time = '2026-10-24 08:00:00', end_time = '2026-10-24 09:00:00',
                    request_window_start = '08:00:00', request_window_end = '09:00:00',
                    requested_duration_minutes = 60
                WHERE resv_id = ?
                """, reservationId);
        String content = qrService.contentForLab(labId);
        MvcResult first = qr(loginAndGetToken(studentA, USER_PASSWORD), reservationId, content);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(first).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
        MvcResult terminal = terminalCheckin(
                openSession(createTerminal(labId)), outsider, USER_PASSWORD).andReturn();
        assertThat(terminal.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(terminal).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(attendanceCount(reservationId)).isEqualTo(2);
        assertThat(qr(loginAndGetToken(studentB, USER_PASSWORD), reservationId, content)
                .getResponse().getStatus()).isEqualTo(403);
        MvcResult repeat = qr(loginAndGetToken(studentA, USER_PASSWORD), reservationId, content);
        assertThat(responseJson(repeat).path("data").path("alreadyCheckedIn").asBoolean()).isTrue();
        assertThat(attendanceCount(reservationId)).isEqualTo(2);
        JsonNode mine = responseJson(mockMvc.perform(get("/api/timetable/mine")
                        .header("Authorization", "Bearer " + loginAndGetToken(studentA, USER_PASSWORD)))
                .andReturn());
        assertThat(mine.path("data")).anySatisfy(item -> {
            assertThat(item.path("reservationId").asLong()).isEqualTo(reservationId);
            assertThat(item.path("checkedIn").asBoolean()).isTrue();
        });
    }

    @Test
    void courseSessionsDoNotDeductNoShowCredit() throws Exception {
        MvcResult created = send(adminToken, "/api/timetable/entries",
                entry(labId, 1, 2, 1, 1, List.of(studentA)));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        long reservationId = responseJson(created).path("data").path("entryId").asLong();
        long courseReservationId = jdbcTemplate.queryForObject(
                "SELECT resv_id FROM t_timetable_session WHERE entry_id = ?", Long.class, reservationId);
        long personalLabId = createLaboratory(2);
        long personalUserId = userIdByAccount(studentB);
        long personalId = createReservation(
                personalUserId, personalLabId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 30),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);
        int teacherCredit = credit(userIdByAccount(teacherAccount));
        long beforeMask = roomMask(LocalDate.of(2026, 10, 24));
        doReturn(Instant.parse("2026-10-24T04:00:00Z")).when(clock).instant();

        jobs.processNoShows();

        assertThat(statusOf(courseReservationId)).isEqualTo("APPROVED");
        assertThat(statusOf(personalId)).isEqualTo("NO_SHOW");
        assertThat(credit(userIdByAccount(teacherAccount))).isEqualTo(teacherCredit);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_violation WHERE resv_id = ?",
                Integer.class, courseReservationId)).isZero();
        assertThat(roomMask(LocalDate.of(2026, 10, 24))).isEqualTo(beforeMask);
    }

    @Test
    void excelImportListsConflictsAndDoesNotWriteAnyRow() throws Exception {
        assertThat(send(adminToken, "/api/timetable/entries",
                entry(labId, 3, 4, 2, 2, List.of(studentA))).getResponse().getStatus()).isEqualTo(200);
        byte[] mixed = workbook(List.of(
                List.of(semesterCode, labCode, "冲突课", teacherAccount, "一班", "周六", "3", "4", "2", "2", studentA),
                List.of(semesterCode, labCode, "空闲课", teacherAccount, "二班", "6", "5", "6", "2", "2", studentB)));
        MvcResult rejected = importFile(adminToken, mixed);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(responseJson(rejected).path("data").path("conflicts")).isNotEmpty();
        assertThat(courseCount("空闲课")).isZero();
        assertThat(courseCount("冲突课")).isZero();

        MvcResult imported = importFile(adminToken, workbook(List.of(
                List.of(semesterCode, labCode, "空闲课", teacherAccount, "二班", "星期六", "5", "6", "2", "2",
                        studentA + "," + studentB))));
        assertThat(imported.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(imported).path("data")).hasSize(1);
        assertThat(courseCount("空闲课")).isEqualTo(1);
        long occupied = TimeSlotMask.of(2, 4).value() | TimeSlotMask.of(4, 6).value();
        assertThat(roomMask(INSIDE_WINDOW)).isEqualTo(occupied);

        MvcResult unknownLab = importFile(adminToken, workbook(List.of(
                List.of(semesterCode, "NO-SUCH-LAB", "无效课", teacherAccount, "三班", "六", "5", "6", "2", "2", studentA))));
        assertThat(unknownLab.getResponse().getStatus()).isEqualTo(400);
        assertThat(courseCount("无效课")).isZero();
    }

    private Map<String, Object> entry(
            long targetLabId, int startPeriod, int endPeriod, int weekStart, int weekEnd, List<String> students) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("semesterId", semesterId);
        body.put("labId", targetLabId);
        body.put("courseName", "数据结构");
        body.put("teacherAccount", teacherAccount);
        body.put("className", "计科2201");
        body.put("dayOfWeek", 6);
        body.put("startPeriod", startPeriod);
        body.put("endPeriod", endPeriod);
        body.put("weekStart", weekStart);
        body.put("weekEnd", weekEnd);
        body.put("studentAccounts", students);
        return body;
    }

    private MvcResult send(String token, String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult qr(String token, long reservationId, String content) throws Exception {
        return mockMvc.perform(post("/api/checkin/qr")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reservationId", reservationId, "content", content))))
                .andReturn();
    }

    private MvcResult importFile(String token, byte[] body) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "timetable.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", body);
        return mockMvc.perform(multipart("/api/timetable/import").file(file)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private static byte[] workbook(List<List<String>> dataRows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            String[] titles = {
                    "学期代码", "实验室代码", "课程名", "教师账号", "班级",
                    "星期", "开始节", "结束节", "开始周", "结束周", "学生账号"};
            for (int index = 0; index < titles.length; index++) {
                header.createCell(index).setCellValue(titles[index]);
            }
            for (int rowIndex = 0; rowIndex < dataRows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = dataRows.get(rowIndex);
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private int entryCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timetable_entry WHERE lab_id = ?", Integer.class, labId);
    }

    private int courseCount(String courseName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timetable_entry WHERE lab_id = ? AND course_name = ?",
                Integer.class, labId, courseName);
    }

    private long roomMask(LocalDate date) {
        List<Long> masks = jdbcTemplate.query(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                (rs, rowNum) -> rs.getLong(1), labId, date);
        return masks.isEmpty() ? 0 : masks.getFirst();
    }

    private long seatMaskSum(LocalDate date) {
        Long sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(b.mask), 0)
                FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, Long.class, labId, date);
        return sum == null ? 0 : sum;
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int attendanceCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_timetable_attendance a
                JOIN t_timetable_session s ON s.session_id = a.session_id
                WHERE s.resv_id = ?
                """, Integer.class, reservationId);
    }

    private int credit(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
    }
}
