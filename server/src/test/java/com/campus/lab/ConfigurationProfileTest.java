package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfigurationProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void noProfileDoesNotActivateLocalOrProvideDevelopmentDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.datasource.username")).isNull();
            assertThat(context.getEnvironment().getProperty("app.jwt.secret")).isNull();
            assertThat(context.getEnvironment().getProperty("app.internal-token")).isNull();
        });
    }

    @Test
    void prodUsesOnlyExplicitlyProvidedConnectionAndSecrets() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "LAB_DB_URL=jdbc:mysql://prod-db.example:3306/reservation_prod",
                        "LAB_DB_USERNAME=prod_service",
                        "LAB_DB_PASSWORD=prod-test-password",
                        "LAB_JWT_SECRET=prod-test-jwt-secret-with-at-least-32-bytes",
                        "AI_INTERNAL_TOKEN=prod-test-internal-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:mysql://prod-db.example:3306/reservation_prod");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                            .isEqualTo("prod_service");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                            .isEqualTo("prod-test-password");
                    assertThat(context.getEnvironment().getProperty("app.jwt.secret"))
                            .isEqualTo("prod-test-jwt-secret-with-at-least-32-bytes");
                    assertThat(context.getEnvironment().getProperty("app.internal-token"))
                            .isEqualTo("prod-test-internal-token");
                });
    }

    @Test
    void scheduledJobsAreEnabledForLocalAndProdButDisabledForTests() {
        contextRunner.run(context ->
                assertThat(context.getEnvironment().getProperty("app.reservation.jobs.enabled"))
                        .isEqualTo("false"));
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context ->
                assertThat(context.getEnvironment().getProperty("app.reservation.jobs.enabled"))
                        .isEqualTo("true"));
        contextRunner.withPropertyValues("spring.profiles.active=local,test").run(context ->
                assertThat(context.getEnvironment().getProperty("app.reservation.jobs.enabled"))
                        .isEqualTo("false"));
        contextRunner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "LAB_DB_URL=jdbc:mysql://prod-db.example:3306/reservation_prod",
                        "LAB_DB_USERNAME=prod_service",
                        "LAB_DB_PASSWORD=prod-test-password",
                        "LAB_JWT_SECRET=prod-test-jwt-secret-with-at-least-32-bytes",
                        "AI_INTERNAL_TOKEN=prod-test-internal-token")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("app.reservation.jobs.enabled"))
                            .isEqualTo("true");
                    assertThat(context.getEnvironment().getProperty("app.checkin.terminal.cookie.secure"))
                            .isEqualTo("true");
                });
    }

    @Test
    void scheduleWorkerProfilesAndGeneticDefaultsAreExplicit() {
        contextRunner.run(context -> {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("app.schedule.worker.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("app.schedule.genetic.population-size")).isEqualTo("80");
            assertThat(environment.getProperty("app.schedule.genetic.generations")).isEqualTo("200");
        });
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context ->
                assertThat(context.getEnvironment().getProperty("app.schedule.worker.enabled"))
                        .isEqualTo("true"));
        contextRunner.withPropertyValues("spring.profiles.active=local,test").run(context -> {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("app.schedule.worker.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("app.schedule.genetic.population-size")).isEqualTo("4");
            assertThat(environment.getProperty("app.schedule.genetic.generations")).isEqualTo("5");
        });
        contextRunner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "LAB_DB_URL=jdbc:mysql://prod-db.example:3306/reservation_prod",
                        "LAB_DB_USERNAME=prod_service",
                        "LAB_DB_PASSWORD=prod-test-password",
                        "LAB_JWT_SECRET=prod-test-jwt-secret-with-at-least-32-bytes",
                        "AI_INTERNAL_TOKEN=prod-test-internal-token")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("app.schedule.worker.enabled")).isEqualTo("true"));
    }

    @Test
    void experimentRecoveryIsPeriodicInLocalAndProdAndDefaultsExceedInstantCapacity() {
        contextRunner.run(context -> {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("app.experiment.recovery.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("app.experiment.recovery.batch-limit"))
                    .isEqualTo("8");
            assertThat(environment.getProperty("app.experiment.recovery.fixed-delay"))
                    .isEqualTo("30s");
        });
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context ->
                assertThat(context.getEnvironment().getProperty("app.experiment.recovery.enabled"))
                        .isEqualTo("true"));
        contextRunner.withPropertyValues("spring.profiles.active=local,test").run(context ->
                assertThat(context.getEnvironment().getProperty("app.experiment.recovery.enabled"))
                        .isEqualTo("false"));
        contextRunner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "LAB_DB_URL=jdbc:mysql://prod-db.example:3306/reservation_prod",
                        "LAB_DB_USERNAME=prod_service",
                        "LAB_DB_PASSWORD=prod-test-password",
                        "LAB_JWT_SECRET=prod-test-jwt-secret-with-at-least-32-bytes",
                        "AI_INTERNAL_TOKEN=prod-test-internal-token")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("app.experiment.recovery.enabled")).isEqualTo("true"));
    }

    @Test
    void publicCheckinRateLimitsHaveSafeDefaults() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("app.checkin.public.account-limit"))
                    .isEqualTo("60");
            assertThat(context.getEnvironment().getProperty("app.checkin.public.session-code-limit"))
                    .isEqualTo("10");
            assertThat(context.getEnvironment().getProperty("app.checkin.public.session-address-limit"))
                    .isEqualTo("30");
            assertThat(context.getEnvironment().getProperty("app.checkin.public.window"))
                    .isEqualTo("60s");
        });
    }

    @Test
    void redisUsesDedicatedTestDatabaseAndLocalStaysOnZero() {
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context ->
                assertThat(context.getEnvironment().getProperty("spring.data.redis.database"))
                        .isEqualTo("0"));
        contextRunner.withPropertyValues("spring.profiles.active=local,test").run(context ->
                assertThat(context.getEnvironment().getProperty("spring.data.redis.database"))
                        .isEqualTo("15"));
    }

    @Test
    void statisticsJobBeanStaysAbsentWhenDisabledDespiteAnotherSchedulingModule() {
        com.campus.lab.statistics.StatisticsRepository repository =
                org.mockito.Mockito.mock(com.campus.lab.statistics.StatisticsRepository.class);
        com.campus.lab.statistics.StatisticsService service =
                org.mockito.Mockito.mock(com.campus.lab.statistics.StatisticsService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(OtherSchedulingConfiguration.class,
                        com.campus.lab.statistics.StatisticsScheduledJobs.class)
                .withBean(com.campus.lab.statistics.StatisticsRepository.class, () -> repository)
                .withBean(com.campus.lab.statistics.StatisticsService.class, () -> service)
                .withBean(java.time.Clock.class, () -> java.time.Clock.fixed(
                        java.time.Instant.parse("2026-10-24T00:00:00Z"),
                        java.time.ZoneId.of("Asia/Shanghai")))
                .withPropertyValues("app.statistics.jobs.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("statisticsScheduledJobs");
                    org.mockito.Mockito.verifyNoInteractions(repository, service);
                });
    }

    @Test
    void prodConnectionAndSecretsAreMandatoryEnvironmentPlaceholders() throws Exception {
        var yaml = new org.yaml.snakeyaml.Yaml();
        try (var input = new org.springframework.core.io.ClassPathResource("application-prod.yml")
                .getInputStream()) {
            java.util.Map<String, Object> root = yaml.load(input);
            var spring = (java.util.Map<String, Object>) root.get("spring");
            var datasource = (java.util.Map<String, Object>) spring.get("datasource");
            var app = (java.util.Map<String, Object>) root.get("app");
            var jwt = (java.util.Map<String, Object>) app.get("jwt");

            assertThat(datasource)
                    .containsEntry("url", "${LAB_DB_URL}")
                    .containsEntry("username", "${LAB_DB_USERNAME}")
                    .containsEntry("password", "${LAB_DB_PASSWORD}");
            assertThat(jwt).containsEntry("secret", "${LAB_JWT_SECRET}");
            assertThat(app).containsEntry("internal-token", "${AI_INTERNAL_TOKEN}");
        }
    }

    @Test
    void wechatSecretsComeFromEnvironmentWithoutProductionDefaults() throws Exception {
        var yaml = new org.yaml.snakeyaml.Yaml();
        try (var input = new org.springframework.core.io.ClassPathResource("application-prod.yml")
                .getInputStream()) {
            java.util.Map<String, Object> root = yaml.load(input);
            var app = (java.util.Map<String, Object>) root.get("app");
            var wechat = (java.util.Map<String, Object>) app.get("wechat");
            assertThat(wechat).containsEntry("app-id", "${WECHAT_APP_ID:}")
                    .containsEntry("app-secret", "${WECHAT_APP_SECRET:}")
                    .containsEntry("checkin-qr-secret", "${WECHAT_CHECKIN_QR_SECRET:}");
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class OtherSchedulingConfiguration {
    }
}
