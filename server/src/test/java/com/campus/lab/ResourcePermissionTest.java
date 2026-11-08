package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ResourcePermissionTest extends AbstractIntegrationTest {

    @Test
    void labAdminCanUseAllowedAuthenticatedReadEndpoint() throws Exception {
        String token = loginAndGetToken("L1001", "Lab@2026");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.account").value("L1001"))
                .andExpect(jsonPath("$.data.roles[0]").value("LAB_ADMIN"));
    }

    @Test
    void labAdminCannotUpdateUserStatus() throws Exception {
        String token = loginAndGetToken("L1001", "Lab@2026");
        String targetAccount = createTestAccount("STUDENT");
        long targetUserId = userIdByAccount(targetAccount);

        mockMvc.perform(patch("/api/admin/users/{id}/status", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(userStatus(targetAccount)).isEqualTo("ACTIVE");
    }

    @Test
    void labAdminCannotListUsers() throws Exception {
        String token = loginAndGetToken("L1001", "Lab@2026");

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void sysAdminCanListUsers() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");

        var result = mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").findValuesAsText("account"))
                .contains("20240001", "T1001", "L1001", "A1001");
    }

    @Test
    void sysAdminCanUpdateUserStatus() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        String targetAccount = createTestAccount("STUDENT");
        long targetUserId = userIdByAccount(targetAccount);

        mockMvc.perform(patch("/api/admin/users/{id}/status", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertThat(userStatus(targetAccount)).isEqualTo("DISABLED");
    }

    @Test
    void adminStatusUpdateRejectsUnsupportedValue() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        String targetAccount = createTestAccount("STUDENT");
        long targetUserId = userIdByAccount(targetAccount);

        mockMvc.perform(patch("/api/admin/users/{id}/status", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(userStatus(targetAccount)).isEqualTo("ACTIVE");
    }

    @Test
    void internalVerifyRequiresInternalCredential() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");

        mockMvc.perform(get("/api/internal/auth/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void internalVerifyRequiresUserBearerToken() throws Exception {
        mockMvc.perform(get("/api/internal/auth/verify")
                        .header("X-Internal-Service-Token", "local-ai-internal-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void internalVerifyReturnsServerConfirmedUser() throws Exception {
        String token = loginAndGetToken("T1001", "Lab@2026");
        long teacherId = userIdByAccount("T1001");

        mockMvc.perform(get("/api/internal/auth/verify")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Internal-Service-Token", "local-ai-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(teacherId))
                .andExpect(jsonPath("$.data.account").value("T1001"))
                .andExpect(jsonPath("$.data.name").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[0]").value("TEACHER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void everyInternalPathRequiresInternalCredential() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");

        mockMvc.perform(get("/api/internal/future-readonly-tool")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private String userStatus(String account) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE account = ?", String.class, account);
    }
}
