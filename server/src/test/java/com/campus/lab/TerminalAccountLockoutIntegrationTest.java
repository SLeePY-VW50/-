package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class TerminalAccountLockoutIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @Test
    void mixedCaseVariantsShareOneCounterAndCorrectPasswordCannotBypassFifthFailureLock() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        String lower = account.toLowerCase(Locale.ROOT);
        String upper = account.toUpperCase(Locale.ROOT);
        String mixed = mixedCase(account);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = ?", Long.class, upper))
                .isEqualTo(userId);
        List<String> attempts = List.of(lower, upper, mixed, lower, upper);

        for (int index = 0; index < 4; index++) {
            assertThat(terminalCheckin(cookie, attempts.get(index), "wrong-password").andReturn()
                    .getResponse().getStatus()).isEqualTo(401);
        }
        Set<String> failuresAfterFour = failureKeys(terminal.deviceId());
        MvcResult fifth = terminalCheckin(cookie, attempts.get(4), "wrong-password").andReturn();
        assertThat(fifth.getResponse().getStatus()).isEqualTo(401);
        Set<String> locksAfterFive = lockKeys(terminal.deviceId());
        Set<String> failuresAfterFive = failureKeys(terminal.deviceId());
        long lockTtl = locksAfterFive.isEmpty()
                ? -2 : redisTemplate.getExpire(locksAfterFive.iterator().next(), TimeUnit.SECONDS);

        MvcResult correctWithAnotherVariant = terminalCheckin(cookie, mixed, USER_PASSWORD).andReturn();

        assertThat(correctWithAnotherVariant.getResponse().getStatus()).isEqualTo(401);
        assertThat(responseJson(correctWithAnotherVariant).path("message").asText())
                .isEqualTo("账号或密码错误");
        String expectedFailureKey = key("fail", terminal.deviceId(), "user:" + userId);
        String expectedLockKey = key("lock", terminal.deviceId(), "user:" + userId);
        assertThat(failuresAfterFour).containsExactly(expectedFailureKey);
        assertThat(locksAfterFive).containsExactly(expectedLockKey);
        assertThat(failuresAfterFive).isEmpty();
        assertThat(lockTtl).isBetween(585L, 600L);
        assertThat(redisTemplate.opsForValue().get(expectedLockKey)).isEqualTo("1");
        assertThat(redisTemplate.getExpire(expectedLockKey, TimeUnit.SECONDS)).isPositive();
        assertThat(failureKeys(terminal.deviceId())).isEmpty();
    }

    @Test
    void successfulAuthenticationWithEquivalentVariantClearsTheSameUserCounter() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        String lower = account.toLowerCase(Locale.ROOT);
        String upper = account.toUpperCase(Locale.ROOT);

        terminalCheckin(cookie, lower, "wrong-password").andReturn();
        terminalCheckin(cookie, upper, "wrong-password").andReturn();
        Set<String> failuresBeforeSuccess = failureKeys(terminal.deviceId());
        MvcResult success = terminalCheckin(cookie, mixedCase(account), USER_PASSWORD).andReturn();
        Set<String> failuresAfterSuccess = failureKeys(terminal.deviceId());

        assertThat(success.getResponse().getStatus()).isEqualTo(404);
        assertThat(failuresAfterSuccess).isEmpty();
        assertThat(failuresBeforeSuccess)
                .containsExactly(key("fail", terminal.deviceId(), "user:" + userId));
        assertThat(lockKeys(terminal.deviceId())).isEmpty();

        for (int attempt = 0; attempt < 4; attempt++) {
            String variant = attempt % 2 == 0 ? lower : upper;
            assertThat(terminalCheckin(cookie, variant, "wrong-password").andReturn()
                    .getResponse().getStatus()).isEqualTo(401);
            assertThat(lockKeys(terminal.deviceId())).isEmpty();
        }
        assertThat(failureKeys(terminal.deviceId()))
                .containsExactly(key("fail", terminal.deviceId(), "user:" + userId));
    }

    @Test
    void unknownAccountsUseNormalizedDigestIdentityWithoutRawAccountInKeys() throws Exception {
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        Cookie cookie = openSession(terminal);
        String unknown = "Missing" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String normalized = normalize(unknown);
        String digest = sha256(normalized);
        String expectedFailureKey = key("fail", terminal.deviceId(), "anonymous:" + digest);
        String expectedLockKey = key("lock", terminal.deviceId(), "anonymous:" + digest);
        List<String> variants = List.of(
                unknown.toLowerCase(Locale.ROOT),
                unknown.toUpperCase(Locale.ROOT),
                "  " + mixedCase(unknown) + "  ",
                unknown.toLowerCase(Locale.ROOT),
                unknown.toUpperCase(Locale.ROOT));

        for (int index = 0; index < 3; index++) {
            assertThat(terminalCheckin(cookie, variants.get(index), "wrong-password").andReturn()
                    .getResponse().getStatus()).isEqualTo(401);
        }
        Set<String> failuresAfterThree = failureKeys(terminal.deviceId());
        terminalCheckin(cookie, variants.get(3), "wrong-password").andReturn();
        MvcResult fifth = terminalCheckin(cookie, variants.get(4), "wrong-password").andReturn();

        assertThat(fifth.getResponse().getStatus()).isEqualTo(401);
        assertThat(failuresAfterThree).containsExactly(expectedFailureKey);
        assertThat(failuresAfterThree.iterator().next())
                .doesNotContain(unknown)
                .doesNotContain(unknown.toLowerCase(Locale.ROOT))
                .doesNotContain(unknown.toUpperCase(Locale.ROOT));
        assertThat(lockKeys(terminal.deviceId())).containsExactly(expectedLockKey);
        assertThat(failureKeys(terminal.deviceId())).isEmpty();
        assertThat(redisTemplate.getExpire(expectedLockKey, TimeUnit.SECONDS)).isBetween(585L, 600L);
    }

    private Set<String> failureKeys(long deviceId) {
        return scanKeys("checkin:terminal:fail:" + deviceId + ":*");
    }

    private Set<String> lockKeys(long deviceId) {
        return scanKeys("checkin:terminal:lock:" + deviceId + ":*");
    }

    private static String key(String kind, long deviceId, String identity) {
        return "checkin:terminal:" + kind + ":" + deviceId + ":" + identity;
    }

    private static String normalize(String account) {
        return Normalizer.normalize(account.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
