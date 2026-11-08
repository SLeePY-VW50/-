package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class TerminalSecretIntegrationTest extends AbstractIntegrationTest {

    @Test
    void seventyFiveUtf8BytesReturn400WithoutCreatingTerminalOrLeakingSecret() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId();
        String plaintext = "密".repeat(25);
        assertThat(plaintext.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        MvcResult result = mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "超字节限制终端",
                                "model", "TEST", "terminalCode", "UTF8-OVER-TERM",
                                "initialSecret", plaintext))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("72")))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(plaintext);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_device WHERE terminal_code = 'UTF8-OVER-TERM'",
                Integer.class)).isZero();
    }

    @Test
    void seventyTwoUtf8BytesAreAcceptedAndStoredOnlyAsBcryptHash() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId();
        String plaintext = "密".repeat(24);
        assertThat(plaintext.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        MvcResult result = mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "字节边界终端",
                                "model", "TEST", "terminalCode", "UTF8-BOUNDARY-TERM",
                                "initialSecret", plaintext))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.terminalCode").value("UTF8-BOUNDARY-TERM"))
                .andExpect(jsonPath("$.data.initialSecret").doesNotExist())
                .andExpect(jsonPath("$.data.terminalSecretHash").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(plaintext);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT terminal_secret_hash FROM t_device WHERE terminal_code = 'UTF8-BOUNDARY-TERM'",
                String.class);
        assertThat(storedHash).startsWith("$2").hasSize(60).isNotEqualTo(plaintext);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(plaintext, storedHash)).isTrue();
    }

    private long labId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-A'", Long.class);
        assertThat(id).isNotNull();
        return id;
    }
}
