package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

class AuditTimeZoneStartupTest {

    private TimeZone originalTimeZone;

    @BeforeEach
    void saveTimeZone() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void nonDefaultJvmTimeZoneIsNormalizedBeforeContextStarts() {
        assertStartupSucceeds("serverTimezone=Asia/Shanghai");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "serverTimezone=UTC",
            "connectionTimeZone=UTC",
            "connectionTimeZone=SERVER",
            "CoNnEcTiOnTiMeZoNe=UTC",
            "serverTimezone=Asia/Shanghai&connectionTimeZone=UTC",
            "connectionTimeZone=SERVER&serverTimezone=LOCAL",
            "serverTimezone=LOCAL&connectionTimeZone=UTC",
            "connectionTimeZone=%55%54%43"
    })
    void unsafeTimeZoneParametersFailBeforeContextStarts(String parameters) {
        String jdbcUrl = jdbcUrl(parameters);
        assertThatThrownBy(() -> {
            try (var context = application().run("--spring.datasource.url=" + jdbcUrl)) {
                assertThat(context).isNotNull();
            }
        }).hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("冲突")
                .hasMessageNotContaining(jdbcUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "connectionTimeZone=LOCAL",
            "serverTimezone=LOCAL",
            "connectionTimeZone=Asia%2FShanghai",
            "serverTimezone=Asia%2FShanghai",
            "connectionTimeZone=GMT%2B08%3A00",
            "serverTimezone=GMT%2B08%3A00",
            "serverTimezone=LOCAL&connectionTimeZone=Asia%2FShanghai",
            "connectionTimeZone=GMT+08:00"
    })
    void safeTimeZoneParametersAllowStartupAndFollowShanghai(String parameters) {
        assertStartupSucceeds(parameters);
    }

    private void assertStartupSucceeds(String parameters) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (var context = application().run("--spring.datasource.url=" + jdbcUrl(parameters))) {
            assertThat(context).isNotNull();
            assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        }
    }

    private static String jdbcUrl(String parameters) {
        return "jdbc:mysql://prod-db.example:3306/reservation_prod"
                + (parameters.isEmpty() ? "" : "?" + parameters);
    }

    private SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.profiles.active=prod",
                        "spring.main.banner-mode=off");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
