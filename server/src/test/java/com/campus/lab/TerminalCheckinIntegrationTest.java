package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@ExtendWith(OutputCaptureExtension.class)
class TerminalCheckinIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @Test
    void invalidAccountsPasswordsRolesAndOverlongPasswordShareOneResponse() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String student = createTestAccount("STUDENT");
        String disabled = createTestAccount("TEACHER");
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE account = ?", disabled);
        String administrator = createTestAccount("SYS_ADMIN");
        String overlong = "密".repeat(25);
        assertThat(overlong.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        List<MvcResult> results = new ArrayList<>();
        results.add(terminalCheckin(cookie, student, "wrong-password").andReturn());
        results.add(terminalCheckin(cookie, "missing-account", USER_PASSWORD).andReturn());
        results.add(terminalCheckin(cookie, disabled, USER_PASSWORD).andReturn());
        results.add(terminalCheckin(cookie, administrator, USER_PASSWORD).andReturn());
        results.add(terminalCheckin(cookie, student, overlong).andReturn());

        List<String> responseBodies = new ArrayList<>();
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            String content = result.getResponse().getContentAsString();
            assertThat(content)
                    .doesNotContain(USER_PASSWORD)
                    .doesNotContain(overlong)
                    .doesNotContain("token");
            responseBodies.add(content);
        }
        String expected = results.getFirst().getResponse().getContentAsString();
        assertThat(responseJson(results.getFirst()).path("message").asText()).isEqualTo("账号或密码错误");
        assertThat(responseBodies).allMatch(expected::equals);
    }

    @Test
    void fiveFailuresLockForTenMinutesAndSuccessfulAuthenticationClearsCounter() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal);
        String account = createTestAccount("STUDENT");
        String lockPattern = "checkin:terminal:lock:" + terminal.deviceId() + ":*";
        String failurePattern = "checkin:terminal:fail:" + terminal.deviceId() + ":*";

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(terminalCheckin(cookie, account, "wrong-password").andReturn()
                    .getResponse().getStatus()).isEqualTo(401);
            assertThat(scanKeys(lockPattern)).isEmpty();
        }
        assertThat(terminalCheckin(cookie, account, "wrong-password").andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        Set<String> lockKeys = scanKeys(lockPattern);
        assertThat(lockKeys).hasSize(1);
        String lockKey = lockKeys.iterator().next();
        assertThat(redisTemplate.getExpire(lockKey, TimeUnit.SECONDS)).isBetween(585L, 600L);
        assertThat(redisTemplate.opsForValue().get(lockKey)).isNotBlank();

        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        redisTemplate.delete(lockKey);
        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(scanKeys(failurePattern)).isEmpty();

        terminalCheckin(cookie, account, "wrong-password").andReturn();
        terminalCheckin(cookie, account, "wrong-password").andReturn();
        assertThat(scanKeys(failurePattern)).hasSize(1);
        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(scanKeys(failurePattern)).isEmpty();
        assertThat(scanKeys(lockPattern)).isEmpty();
    }

    @Test
    void anotherLaboratoryOrNoOwnReservationReturnsNotFound() throws Exception {
        long firstLab = createLaboratory(2);
        long secondLab = createLaboratory(2);
        Cookie firstCookie = openSession(createTerminal(firstLab));
        Cookie secondCookie = openSession(createTerminal(secondLab));
        String owner = createTestAccount("STUDENT");
        String other = createTestAccount("STUDENT");
        long reservationId = createReservation(
                userIdByAccount(owner), firstLab, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);

        MvcResult wrongLab = terminalCheckin(secondCookie, owner, USER_PASSWORD).andReturn();
        MvcResult noReservation = terminalCheckin(firstCookie, other, USER_PASSWORD).andReturn();

        assertThat(wrongLab.getResponse().getStatus()).isEqualTo(404);
        assertThat(noReservation.getResponse().getStatus()).isEqualTo(404);
        assertThat(responseJson(wrongLab).path("message").asText()).isEqualTo("当前没有可签到预约");
        assertThat(responseJson(noReservation).path("message").asText()).isEqualTo("当前没有可签到预约");
        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(checkinLogCount(reservationId)).isZero();
    }

    @Test
    void uniqueReservationChecksInOnceAndRepeatReturnsAlreadyCheckedIn() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);

        MvcResult first = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = responseJson(first).path("data");
        assertThat(firstBody.path("choiceRequired").asBoolean()).isFalse();
        assertThat(firstBody.path("reservationId").asLong()).isEqualTo(reservationId);
        assertThat(firstBody.path("status").asText()).isEqualTo("IN_USE");
        assertThat(firstBody.path("checkinChannel").asText()).isEqualTo("TERMINAL_ACCOUNT");
        assertThat(firstBody.path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(firstBody.path("checkinTime").asText()).isNotBlank();
        assertThat(firstBody.path("token").isMissingNode()).isTrue();
        LocalDateTime checkinTime = jdbcTemplate.queryForObject(
                "SELECT checkin_time FROM t_reservation WHERE resv_id = ?", LocalDateTime.class, reservationId);
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);

        MvcResult repeated = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        JsonNode repeatedBody = responseJson(repeated).path("data");
        assertThat(repeatedBody.path("reservationId").asLong()).isEqualTo(reservationId);
        assertThat(repeatedBody.path("alreadyCheckedIn").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkin_time FROM t_reservation WHERE resv_id = ?", LocalDateTime.class, reservationId))
                .isEqualTo(checkinTime);
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);
    }

    @Test
    void existingInUseReservationDoesNotBlockAnotherApprovedCheckin() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long inUse = createReservation(
                userId, labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);
        long approved = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);

        MvcResult result = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(result).path("data").path("reservationId").asLong()).isEqualTo(approved);
        assertThat(responseJson(result).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(statusOf(inUse)).isEqualTo("IN_USE");
        assertThat(statusOf(approved)).isEqualTo("IN_USE");
        assertThat(checkinLogCount(approved)).isEqualTo(1);
    }

    @Test
    void multipleReservationsUseTwoMinuteChoiceAndSelectionCannotBeReused() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long firstReservation = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);
        long secondReservation = createReservation(
                userId, labId, "ROOM", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);

        MvcResult choices = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(choices.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = responseJson(choices).path("data");
        assertThat(data.path("choiceRequired").asBoolean()).isTrue();
        assertThat(data.path("expiresIn").asLong()).isEqualTo(120);
        assertThat(data.path("reservations")).hasSize(2);
        assertThat(data.path("reservations").toString())
                .contains(Long.toString(firstReservation), Long.toString(secondReservation))
                .doesNotContain("password", "purpose", "userId");
        String choiceToken = data.path("choiceToken").asText();
        assertThat(choiceToken).hasSizeGreaterThanOrEqualTo(32);
        String choiceKey = "checkin:terminal:choice:" + choiceToken;
        assertThat(redisTemplate.getExpire(choiceKey, TimeUnit.SECONDS)).isBetween(115L, 120L);
        JsonNode storedChoice = objectMapper.readTree(redisTemplate.opsForValue().get(choiceKey));
        List<String> fields = new ArrayList<>();
        storedChoice.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("userId", "labId", "reservationIds");
        assertThat(storedChoice.path("userId").asLong()).isEqualTo(userId);
        assertThat(storedChoice.path("labId").asLong()).isEqualTo(labId);
        assertThat(storedChoice.path("reservationIds")).hasSize(2);

        MvcResult selected = select(cookie, choiceToken, firstReservation).andReturn();
        assertThat(selected.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(selected).path("data").path("reservationId").asLong())
                .isEqualTo(firstReservation);
        assertThat(redisTemplate.hasKey(choiceKey)).isFalse();
        assertThat(statusOf(firstReservation)).isEqualTo("IN_USE");
        assertThat(checkinLogCount(firstReservation)).isEqualTo(1);

        MvcResult reused = select(cookie, choiceToken, secondReservation).andReturn();
        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
        assertThat(statusOf(secondReservation)).isEqualTo("APPROVED");
        assertThat(checkinLogCount(secondReservation)).isZero();
    }

    @Test
    void expiredForgedAndWrongReservationChoicesFailAndNeverRestoreToken() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long firstReservation = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);
        long secondReservation = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);

        String expiredToken = choiceToken(cookie, account);
        redisTemplate.expire(choiceKey(expiredToken), Duration.ofMillis(-1));
        assertThat(select(cookie, expiredToken, firstReservation).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(statusOf(firstReservation)).isEqualTo("APPROVED");

        String consumedToken = choiceToken(cookie, account);
        String other = createTestAccount("STUDENT");
        long outsideReservation = createReservation(
                userIdByAccount(other), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);
        MvcResult wrongReservation = select(cookie, consumedToken, outsideReservation).andReturn();
        assertThat(wrongReservation.getResponse().getStatus()).isEqualTo(409);
        assertThat(redisTemplate.hasKey(choiceKey(consumedToken))).isFalse();
        assertThat(select(cookie, consumedToken, secondReservation).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(select(cookie, "forged-choice-token", firstReservation).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(statusOf(firstReservation)).isEqualTo("APPROVED");
        assertThat(statusOf(secondReservation)).isEqualTo("APPROVED");
    }

    @Test
    void passwordsAndTerminalSecretsAreAbsentFromResponsesAndLogs(CapturedOutput output) throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal);
        String account = createTestAccount("STUDENT");
        String wrongPassword = "wrong-password-never-log";

        MvcResult result = terminalCheckin(cookie, account, wrongPassword).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(terminal.secret(), USER_PASSWORD, wrongPassword);
        assertThat(output.getAll())
                .doesNotContain(terminal.secret(), USER_PASSWORD, wrongPassword);
    }

    private String choiceToken(Cookie cookie, String account) throws Exception {
        MvcResult result = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = responseJson(result).path("data");
        assertThat(data.path("choiceRequired").asBoolean()).isTrue();
        return data.path("choiceToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions select(
            Cookie cookie, String choiceToken, long reservationId) throws Exception {
        return mockMvc.perform(post("/api/checkin/terminal/select")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "choiceToken", choiceToken,
                        "reservationId", reservationId))));
    }

    private String choiceKey(String token) {
        return "checkin:terminal:choice:" + token;
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int checkinLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'APPROVED' AND to_status = 'IN_USE'
                """, Integer.class, reservationId);
    }
}
