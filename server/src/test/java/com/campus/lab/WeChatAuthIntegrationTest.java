package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.auth.AuthService;
import com.campus.lab.auth.wechat.WeChatGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class WeChatAuthIntegrationTest extends AbstractIntegrationTest {
    @MockitoBean private WeChatGateway gateway;
    @MockitoSpyBean private AuthService authService;
    @Autowired private StringRedisTemplate redis;
    private final List<String> tickets = new ArrayList<>();

    @AfterEach
    void removeTickets() {
        for (String ticket : tickets) {
            redis.delete("auth:wechat:bind:" + ticket);
            redis.delete("auth:wechat:bind-fail:" + ticket);
            redis.delete("auth:wechat:bind-lock:" + ticket);
        }
    }

    @Test
    void boundOpenidLogsInAndUnboundOpenidMustBindBeforeReceivingJwt() throws Exception {
        String account = createTestAccount("STUDENT");
        String openid = "openid-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", openid, account);
        when(gateway.openidForCode("bound-code")).thenReturn(openid);
        mockMvc.perform(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "bound-code"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bound").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.account").value(account))
                .andExpect(jsonPath("$.data.openid").doesNotExist());

        String unboundAccount = createTestAccount("STUDENT");
        String unboundOpenid = "openid-" + UUID.randomUUID();
        when(gateway.openidForCode("new-code")).thenReturn(unboundOpenid);
        JsonNode unbound = response(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", "new-code"))));
        assertThat(unbound.path("data").path("bound").asBoolean()).isFalse();
        assertThat(unbound.path("data").path("token").isNull()).isTrue();
        String ticket = unbound.path("data").path("bindTicket").asText();
        tickets.add(ticket);
        assertThat(redis.opsForValue().get("auth:wechat:bind:" + ticket)).isEqualTo(unboundOpenid);

        mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bindTicket", ticket, "account", unboundAccount, "password", "Lab@2026"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());
        assertThat(redis.hasKey("auth:wechat:bind:" + ticket)).isFalse();
        mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bindTicket", ticket, "account", unboundAccount, "password", "Lab@2026"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongPasswordsInvalidateTicketAndDuplicateOpenidIsRejected() throws Exception {
        String first = createTestAccount("STUDENT");
        String second = createTestAccount("STUDENT");
        String openid = "openid-" + UUID.randomUUID();
        when(gateway.openidForCode("limited-code")).thenReturn(openid);
        String ticket = ticket("limited-code");
        for (int attempt = 0; attempt < 4; attempt++) {
            bind(ticket, first, "wrong-password").andExpect(status().isUnauthorized());
        }
        bind(ticket, first, "wrong-password").andExpect(status().isUnauthorized());
        bind(ticket, first, "Lab@2026").andExpect(status().isUnauthorized());

        when(gateway.openidForCode("duplicate-code")).thenReturn(openid);
        String duplicateTicket = ticket("duplicate-code");
        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", openid, first);
        bind(duplicateTicket, second, "Lab@2026").andExpect(status().isConflict());
    }

    @Test
    void disabledAccountExpiredTicketAndAlreadyBoundAccountAreRejected() throws Exception {
        String disabled = createTestAccount("STUDENT");
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE account = ?", disabled);
        String boundOpenid = "openid-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?", boundOpenid, disabled);
        when(gateway.openidForCode("disabled-code")).thenReturn(boundOpenid);
        mockMvc.perform(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "disabled-code"))))
                .andExpect(status().isUnauthorized());

        String active = createTestAccount("STUDENT");
        when(gateway.openidForCode("expired-code")).thenReturn("openid-" + UUID.randomUUID());
        String expired = ticket("expired-code");
        redis.expire("auth:wechat:bind:" + expired, java.time.Duration.ofMillis(-1));
        bind(expired, active, "Lab@2026").andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE sys_user SET wechat_openid = ? WHERE account = ?",
                "openid-existing-" + UUID.randomUUID(), active);
        when(gateway.openidForCode("other-openid-code")).thenReturn("openid-new-" + UUID.randomUUID());
        String otherOpenid = ticket("other-openid-code");
        bind(otherOpenid, active, "Lab@2026").andExpect(status().isConflict());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSuccessfulBindAndTicketReuseAllowOnlyOneSuccess() throws Exception {
        String account = createTestAccount("STUDENT");
        try {
            when(gateway.openidForCode("concurrent-code")).thenReturn("openid-" + UUID.randomUUID());
            String ticket = ticket("concurrent-code");
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Integer>> attempts = new ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    attempts.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return bind(ticket, account, "Lab@2026").andReturn().getResponse().getStatus();
                    }));
                }
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> attempt : attempts) {
                    statuses.add(attempt.get(30, TimeUnit.SECONDS));
                }
                assertThat(statuses).containsExactlyInAnyOrder(200, 401);
            } finally {
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(redis.hasKey("auth:wechat:bind:" + ticket)).isFalse();
            assertThat(redis.hasKey("auth:wechat:bind-fail:" + ticket)).isFalse();
            assertThat(redis.hasKey("auth:wechat:bind-lock:" + ticket)).isFalse();
        } finally {
            removeAccount(account);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fifthFailureCompletedFirstInvalidatesConcurrentSuccessfulAttempt() throws Exception {
        String account = createTestAccount("STUDENT");
        try {
            when(gateway.openidForCode("failure-race-code")).thenReturn("openid-" + UUID.randomUUID());
            String ticket = ticket("failure-race-code");
            for (int attempt = 0; attempt < 4; attempt++) {
                bind(ticket, account, "wrong-password").andExpect(status().isUnauthorized());
            }
            assertThat(redis.getExpire("auth:wechat:bind-fail:" + ticket)).isBetween(1L, 300L);

            CountDownLatch failureAuthenticating = new CountDownLatch(1);
            CountDownLatch allowFailure = new CountDownLatch(1);
            CountDownLatch successAuthenticating = new CountDownLatch(1);
            CountDownLatch allowSuccess = new CountDownLatch(1);
            doAnswer(invocation -> {
                failureAuthenticating.countDown();
                assertThat(allowFailure.await(30, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }).when(authService).userByCredentials(account, "wrong-password");
            doAnswer(invocation -> {
                successAuthenticating.countDown();
                assertThat(allowSuccess.await(30, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }).when(authService).userByCredentials(account, "Lab@2026");

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                Future<Integer> failure = executor.submit(() -> bind(ticket, account, "wrong-password")
                        .andReturn().getResponse().getStatus());
                assertThat(failureAuthenticating.await(30, TimeUnit.SECONDS)).isTrue();
                Future<Integer> success = executor.submit(() -> bind(ticket, account, "Lab@2026")
                        .andReturn().getResponse().getStatus());
                if (!successAuthenticating.await(2, TimeUnit.SECONDS)) {
                    assertThat(redis.hasKey("auth:wechat:bind-lock:" + ticket)).isTrue();
                }

                allowFailure.countDown();
                assertThat(failure.get(30, TimeUnit.SECONDS)).isEqualTo(401);
                assertThat(redis.hasKey("auth:wechat:bind:" + ticket)).isFalse();
                assertThat(redis.hasKey("auth:wechat:bind-fail:" + ticket)).isFalse();
                allowSuccess.countDown();
                assertThat(success.get(30, TimeUnit.SECONDS)).isEqualTo(401);
            } finally {
                allowFailure.countDown();
                allowSuccess.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removeAccount(account);
        }
    }

    private void removeAccount(String account) {
        jdbcTemplate.update("""
                DELETE ur FROM sys_user_role ur
                JOIN sys_user u ON u.user_id = ur.user_id
                WHERE u.account = ?
                """, account);
        jdbcTemplate.update("DELETE FROM sys_user WHERE account = ?", account);
    }

    private String ticket(String code) throws Exception {
        JsonNode body = response(post("/api/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))));
        String ticket = body.path("data").path("bindTicket").asText();
        tickets.add(ticket);
        return ticket;
    }

    private org.springframework.test.web.servlet.ResultActions bind(
            String ticket, String account, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/wechat/bind").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "bindTicket", ticket, "account", account, "password", password))));
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
