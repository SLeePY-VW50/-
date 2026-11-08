package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

class PublicCheckinRateLimitIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @MockitoSpyBean
    private PasswordEncoder encodingSpy;

    @MockitoSpyBean
    private JdbcTemplate jdbcSpy;

    private final Set<String> ownedRateKeys = new HashSet<>();

    @DynamicPropertySource
    static void smallRateLimits(DynamicPropertyRegistry registry) {
        registry.add("app.checkin.public.account-limit", () -> 2);
        registry.add("app.checkin.public.session-code-limit", () -> 2);
        registry.add("app.checkin.public.session-address-limit", () -> 3);
        registry.add("app.checkin.public.window", () -> "60s");
    }

    @AfterEach
    void resetSpiesAndDeleteOwnedRateKeys() {
        try {
            if (!ownedRateKeys.isEmpty()) {
                redisTemplate.delete(ownedRateKeys);
                ownedRateKeys.clear();
            }
        } finally {
            Mockito.reset(encodingSpy, jdbcSpy);
        }
    }

    @Test
    void accountEndpointUsesDeviceAndDirectAddressAndSkipsBcryptWhenOverLimit() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String directAddress = "10.20.0.1";
        Cookie cookie = openSession(terminal, directAddress);
        String account = createTestAccount("STUDENT");

        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult result = accountRequest(
                    terminal, cookie, account, "wrong-password", directAddress, "203.0.113." + attempt);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
        Mockito.clearInvocations(encodingSpy);
        MvcResult limited = accountRequest(
                terminal, cookie, account, "wrong-password", directAddress, "203.0.113.99");

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(responseJson(limited).path("message").asText()).isEqualTo("请求过于频繁，请稍后重试");
        assertThat(limited.getResponse().getContentAsString()).doesNotContain(account, "wrong-password");
        verify(encodingSpy, never()).matches(any(), any());
        String key = accountKey(terminal.deviceId(), directAddress);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("3");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void sessionCodeLimitUsesNormalizedDigestAndSkipsBcryptWhenOverLimit() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String code = terminal.terminalCode();
        List<String> variants = List.of(
                code.toLowerCase(Locale.ROOT), code.toUpperCase(Locale.ROOT), mixedCase(code));

        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult result = sessionRequest(
                    variants.get(attempt), "wrong-secret", "10.30.0." + (attempt + 1), null);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
        Mockito.clearInvocations(encodingSpy);
        MvcResult limited = sessionRequest(variants.get(2), "wrong-secret", "10.30.0.3", null);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(responseJson(limited).path("message").asText()).isEqualTo("请求过于频繁，请稍后重试");
        assertThat(limited.getResponse().getContentAsString()).doesNotContain("wrong-secret");
        verify(encodingSpy, never()).matches(any(), any());
        String key = sessionCodeKey(code);
        assertThat(key).doesNotContain(code);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("3");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void sessionAddressLimitCannotBeBypassedByChangingCodesOrForwardedHeaders() throws Exception {
        String directAddress = "10.40.0.1";

        for (int attempt = 0; attempt < 3; attempt++) {
            MvcResult result = sessionRequest(
                    "missing-" + UUID.randomUUID(), "wrong-secret", directAddress,
                    "198.51.100." + attempt);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
        Mockito.clearInvocations(encodingSpy);
        MvcResult limited = sessionRequest(
                "missing-" + UUID.randomUUID(), "wrong-secret", directAddress, "198.51.100.99");

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(responseJson(limited).path("message").asText()).isEqualTo("请求过于频繁，请稍后重试");
        assertThat(limited.getResponse().getContentAsString()).doesNotContain("wrong-secret");
        verify(encodingSpy, never()).matches(any(), any());
        String key = sessionAddressKey(directAddress);
        assertThat(key).doesNotContain(directAddress);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("4");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void oversizedAuthenticationFieldsReturnGenericBadRequestWithoutBcryptOrEcho() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal, "10.50.0.1");
        String account = createTestAccount("STUDENT");
        String longCode = "X".repeat(65);
        String longSecret = "S".repeat(129);
        String longAccount = "A".repeat(33);
        String longPassword = "P".repeat(129);
        Mockito.clearInvocations(encodingSpy);

        MvcResult codeResult = sessionRequest(longCode, "bad", "10.50.0.2", null);
        MvcResult secretResult = sessionRequest(
                terminal.terminalCode(), longSecret, "10.50.0.3", null);
        MvcResult accountResult = accountRequest(
                terminal, cookie, longAccount, "bad", "10.50.0.1", null);
        MvcResult passwordResult = accountRequest(
                terminal, cookie, account, longPassword, "10.50.0.1", null);

        for (MvcResult result : List.of(codeResult, secretResult, accountResult, passwordResult)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(responseJson(result).path("message").asText()).isEqualTo("请求参数无效");
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(longCode, longSecret, longAccount, longPassword);
        }
        verify(encodingSpy, never()).matches(any(), any());
    }

    @Test
    void existingAccountAndRolesAreLoadedByOneQuery() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal, "10.60.0.1");
        String account = createTestAccount("STUDENT");
        Mockito.clearInvocations(jdbcSpy);

        MvcResult result = accountRequest(
                terminal, cookie, account, USER_PASSWORD, "10.60.0.1", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        List<String> statements = Mockito.mockingDetails(jdbcSpy).getInvocations().stream()
                .filter(invocation -> (invocation.getMethod().getName().equals("query")
                        || invocation.getMethod().getName().equals("queryForList"))
                        && invocation.getArguments().length > 0
                        && invocation.getArgument(0) instanceof String)
                .map(invocation -> (String) invocation.getArgument(0))
                .filter(sql -> sql.contains("sys_user") || sql.contains("sys_role"))
                .toList();
        assertThat(statements.stream().distinct()).hasSize(1);
        assertThat(statements.getFirst()).contains("GROUP_CONCAT");
    }

    private Cookie openSession(TerminalFixture terminal, String remoteAddress) throws Exception {
        MvcResult result = sessionRequest(
                terminal.terminalCode(), terminal.secret(), remoteAddress, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = result.getResponse().getCookie("lab_terminal");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MvcResult accountRequest(
            TerminalFixture terminal,
            Cookie cookie,
            String account,
            String password,
            String remoteAddress,
            String forwardedFor) throws Exception {
        ownedRateKeys.add(accountKey(terminal.deviceId(), remoteAddress));
        MockHttpServletRequestBuilder builder = post("/api/checkin/terminal")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("account", account, "password", password)))
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                });
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private MvcResult sessionRequest(
            String terminalCode,
            String secret,
            String remoteAddress,
            String forwardedFor) throws Exception {
        ownedRateKeys.add(sessionCodeKey(terminalCode));
        ownedRateKeys.add(sessionAddressKey(remoteAddress));
        MockHttpServletRequestBuilder builder = post("/api/checkin/terminal/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "terminalCode", terminalCode, "terminalSecret", secret)))
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                });
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private static String accountKey(long deviceId, String remoteAddress) {
        return "checkin:public:account:" + deviceId + ":" + sha256(remoteAddress);
    }

    private static String sessionCodeKey(String terminalCode) {
        return "checkin:public:session:code:" + sha256(normalize(terminalCode));
    }

    private static String sessionAddressKey(String remoteAddress) {
        return "checkin:public:session:address:" + sha256(remoteAddress);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String mixedCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(index % 2 == 0
                    ? Character.toLowerCase(character)
                    : Character.toUpperCase(character));
        }
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
