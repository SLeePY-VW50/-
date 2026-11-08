package com.campus.lab.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class AuditTimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SHANGHAI = "Asia/Shanghai";
    private static final Pattern TIME_ZONE_PARAMETER = Pattern.compile(
            "[?&](serverTimezone|connectionTimeZone)=([^&]*)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> EQUIVALENT_OFFSETS = Set.of(
            "+08", "+0800", "+08:00",
            "GMT+8", "GMT+0800", "GMT+08:00",
            "UTC+8", "UTC+0800", "UTC+08:00");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        TimeZone.setDefault(TimeZone.getTimeZone(SHANGHAI));
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql:")) {
            return;
        }
        Matcher matcher = TIME_ZONE_PARAMETER.matcher(jdbcUrl);
        while (matcher.find()) {
            String parameter = matcher.group(1);
            String value = decode(parameter, matcher.group(2));
            if (!isSafe(value)) {
                throw new IllegalStateException(
                        "spring.datasource.url 的 " + parameter + " 与 Asia/Shanghai 冲突: " + value);
            }
        }
    }

    private static boolean isSafe(String value) {
        return "LOCAL".equalsIgnoreCase(value)
                || SHANGHAI.equals(value)
                || EQUIVALENT_OFFSETS.contains(value.toUpperCase(Locale.ROOT));
    }

    private static String decode(String parameter, String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "spring.datasource.url 的 " + parameter + " 无法按URL解码", exception);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
