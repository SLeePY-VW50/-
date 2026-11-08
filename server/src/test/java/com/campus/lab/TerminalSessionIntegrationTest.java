package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class TerminalSessionIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @Test
    void correctTerminalCreatesOpaqueEightHourSessionAndStrictCookie() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);

        MvcResult result = terminalSession(terminal.terminalCode(), terminal.secret());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = responseJson(result);
        assertThat(body.path("code").asInt()).isZero();
        assertThat(body.path("data").path("expiresIn").asLong()).isEqualTo(28_800);
        assertThat(body.path("data").path("lab").path("labId").asLong()).isEqualTo(labId);
        assertThat(body.path("data").path("lab").path("name").asText()).isNotBlank();
        assertThat(body.path("data").path("token").isMissingNode()).isTrue();
        assertThat(body.path("data").path("jwt").isMissingNode()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(terminal.secret());

        Cookie cookie = result.getResponse().getCookie("lab_terminal");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).hasSizeGreaterThanOrEqualTo(32).isNotEqualTo(terminal.secret());
        assertThat(cookie.getMaxAge()).isEqualTo(28_800);
        assertThat(cookie.getPath()).isEqualTo("/api/checkin/terminal");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("SameSite=Strict")
                .contains("Max-Age=28800")
                .contains("HttpOnly");

        String key = "checkin:terminal:session:" + cookie.getValue();
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(28_785L, 28_800L);
        String stored = redisTemplate.opsForValue().get(key);
        assertThat(stored).isNotNull().doesNotContain(terminal.secret());
        JsonNode session = objectMapper.readTree(stored);
        List<String> fields = new ArrayList<>();
        session.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("deviceId", "labId");
        assertThat(session.path("deviceId").asLong()).isEqualTo(terminal.deviceId());
        assertThat(session.path("labId").asLong()).isEqualTo(labId);
    }

    @Test
    void wrongTerminalInputsAndOverlongSecretShareOneUnauthorizedResponse() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String overlong = "密".repeat(25);
        assertThat(overlong.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        MvcResult unknown = terminalSession("missing-terminal", "wrong");
        MvcResult wrongSecret = terminalSession(terminal.terminalCode(), "wrong");
        MvcResult tooLong = terminalSession(terminal.terminalCode(), overlong);

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongSecret.getResponse().getStatus()).isEqualTo(401);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongSecret.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString())
                .isEqualTo(tooLong.getResponse().getContentAsString());
        assertThat(responseJson(unknown).path("message").asText()).isEqualTo("终端凭证无效");
        assertThat(tooLong.getResponse().getContentAsString()).doesNotContain(overlong);
        assertThat(unknown.getResponse().getCookie("lab_terminal")).isNull();
        assertThat(wrongSecret.getResponse().getCookie("lab_terminal")).isNull();
        assertThat(tooLong.getResponse().getCookie("lab_terminal")).isNull();
    }

    @Test
    void disabledDeviceOrLaboratoryRejectsNewAndExistingSessionsWithoutStudentJwt() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String account = createTestAccount("STUDENT");

        jdbcTemplate.update("UPDATE t_device SET status = 'DISABLED' WHERE device_id = ?", terminal.deviceId());
        MvcResult disabledDevice = terminalSession(terminal.terminalCode(), terminal.secret());
        assertThat(disabledDevice.getResponse().getStatus()).isEqualTo(401);
        assertThat(responseJson(disabledDevice).path("message").asText()).isEqualTo("终端凭证无效");

        jdbcTemplate.update("UPDATE t_device SET status = 'ACTIVE' WHERE device_id = ?", terminal.deviceId());
        Cookie deviceCookie = openSession(terminal);
        jdbcTemplate.update("UPDATE t_device SET status = 'DISABLED' WHERE device_id = ?", terminal.deviceId());
        MvcResult existingDevice = terminalCheckin(deviceCookie, account, USER_PASSWORD).andReturn();
        assertThat(existingDevice.getResponse().getStatus()).isEqualTo(401);
        assertThat(existingDevice.getResponse().getContentAsString()).doesNotContain("token");

        jdbcTemplate.update("UPDATE t_device SET status = 'ACTIVE' WHERE device_id = ?", terminal.deviceId());
        jdbcTemplate.update("UPDATE t_lab SET status = 'MAINTENANCE' WHERE lab_id = ?", labId);
        MvcResult disabledLab = terminalSession(terminal.terminalCode(), terminal.secret());
        assertThat(disabledLab.getResponse().getStatus()).isEqualTo(401);
        assertThat(responseJson(disabledLab).path("message").asText())
                .isEqualTo(responseJson(disabledDevice).path("message").asText());
        Cookie labCookie = openSessionWithActiveLab(terminal, labId);
        jdbcTemplate.update("UPDATE t_lab SET status = 'DISABLED' WHERE lab_id = ?", labId);
        assertThat(terminalCheckin(labCookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void expiredInvalidAndForgedSessionsAreRejected() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String account = createTestAccount("STUDENT");
        Cookie cookie = openSession(terminal);
        String key = "checkin:terminal:session:" + cookie.getValue();

        redisTemplate.expire(key, Duration.ofMillis(-1));
        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        Cookie forged = new Cookie("lab_terminal", "forged-terminal-session");
        forged.setPath("/api/checkin/terminal");
        MvcResult result = terminalCheckin(forged, account, USER_PASSWORD).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("token");
    }

    private Cookie openSessionWithActiveLab(TerminalFixture terminal, long labId) throws Exception {
        jdbcTemplate.update("UPDATE t_lab SET status = 'ACTIVE' WHERE lab_id = ?", labId);
        return openSession(terminal);
    }
}
