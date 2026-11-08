package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Import(TestDatabaseConfiguration.class)
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String loginAndGetToken(String account, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "account", account,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("code").asInt()).isZero();
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    protected long userIdByAccount(String account) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = ?", Long.class, account);
        assertThat(userId).isNotNull();
        return userId;
    }

    protected String createTestAccount(String roleCode) {
        String account = "U" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO sys_user (account, name, password_hash, credit_score, status)
                SELECT ?, '独立测试账号', password_hash, 100, 'ACTIVE'
                FROM sys_user WHERE account = '20240001'
                """, account);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                SELECT u.user_id, r.role_id
                FROM sys_user u
                JOIN sys_role r ON r.role_code = ?
                WHERE u.account = ?
                """, roleCode, account);
        return account;
    }
}
