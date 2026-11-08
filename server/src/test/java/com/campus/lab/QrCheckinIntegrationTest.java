package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.checkin.qr.CheckinQrService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class QrCheckinIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @Autowired
    private CheckinQrService qrService;

    @Test
    void validCodeChecksInOnceAndWrongLaboratoryOrSignatureDoesNotChangeState() throws Exception {
        long labId = createLaboratory(2);
        long otherLabId = createLaboratory(2);
        String account = createTestAccount("STUDENT");
        String token = loginAndGetToken(account, USER_PASSWORD);
        long reservationId = createReservation(userIdByAccount(account), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);

        String content = qrService.contentForLab(labId);
        MvcResult first = qr(token, reservationId, content).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(first).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(responseJson(first).path("data").path("reservation")
                .path("checkinChannel").asText()).isEqualTo("WECHAT_QR");
        MvcResult repeated = qr(token, reservationId, content).andReturn();
        assertThat(responseJson(repeated).path("data").path("alreadyCheckedIn").asBoolean()).isTrue();
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);

        assertThat(qr(token, reservationId, qrService.contentForLab(otherLabId)).andReturn()
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(qr(token, reservationId, "labspace://checkin?v=1&labId=" + labId
                + "&signature=invalid").andReturn().getResponse().getStatus()).isEqualTo(400);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = content.charAt(content.length() - 1);
        String nonCanonicalContent = content.substring(0, content.length() - 1)
                + alphabet.charAt(alphabet.indexOf(last) ^ 3);
        assertThat(qr(token, reservationId, nonCanonicalContent).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
    }

    @Test
    void nonOwnerOutsideWindowAndAnonymousRequestsCannotChangeReservation() throws Exception {
        long labId = createLaboratory(2);
        String owner = createTestAccount("STUDENT");
        String other = createTestAccount("STUDENT");
        String ownerToken = loginAndGetToken(owner, USER_PASSWORD);
        String otherToken = loginAndGetToken(other, USER_PASSWORD);
        long reservationId = createReservation(userIdByAccount(owner), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), false);
        String content = qrService.contentForLab(labId);

        assertThat(qr(otherToken, reservationId, content).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        mockMvc.perform(post("/api/checkin/qr").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reservationId", reservationId, "content", content))))
                .andExpect(status().isUnauthorized());
        jdbcTemplate.update("UPDATE t_reservation SET start_time = '2026-10-24 10:00:00', "
                + "end_time = '2026-10-24 11:00:00', request_window_start = '10:00:00', "
                + "request_window_end = '11:00:00', requested_duration_minutes = 60 "
                + "WHERE resv_id = ?", reservationId);
        assertThat(qr(ownerToken, reservationId, content).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(checkinLogCount(reservationId)).isZero();
    }

    @Test
    void onlySystemAdministratorCanReadCheckinCode() throws Exception {
        long labId = createLaboratory(1);
        String admin = createTestAccount("SYS_ADMIN");
        String student = createTestAccount("STUDENT");
        mockMvc.perform(get("/api/admin/resources/labs/{labId}/checkin-code", labId)
                        .header("Authorization", "Bearer " + loginAndGetToken(admin, USER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.content").isNotEmpty());
        mockMvc.perform(get("/api/admin/resources/labs/{labId}/checkin-code", labId)
                        .header("Authorization", "Bearer " + loginAndGetToken(student, USER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdministratorGetsNotFoundForMissingLaboratoryCheckinCode() throws Exception {
        String admin = createTestAccount("SYS_ADMIN");

        mockMvc.perform(get("/api/admin/resources/labs/{labId}/checkin-code", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + loginAndGetToken(admin, USER_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions qr(
            String token, long reservationId, String content) throws Exception {
        return mockMvc.perform(post("/api/checkin/qr").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "reservationId", reservationId, "content", content))));
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int checkinLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_reservation_log "
                + "WHERE resv_id = ? AND from_status = 'APPROVED' AND to_status = 'IN_USE'",
                Integer.class, reservationId);
    }
}
