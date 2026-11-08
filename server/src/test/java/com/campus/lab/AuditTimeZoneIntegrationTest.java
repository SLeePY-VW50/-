package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class AuditTimeZoneIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private Clock clock;

    @Test
    void jvmAndMysqlSessionUseShanghaiWithoutChangingGlobalDatabaseTimeZone() {
        assertThat(dataSource.getConnectionInitSql()).isEqualTo("SET time_zone = '+08:00'");
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(jdbcTemplate.queryForObject("SELECT @@SESSION.time_zone", String.class))
                .isEqualTo("+08:00");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, Integer.class)).isEqualTo(8 * 60 * 60);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "LOCAL"})
    void timestampRoundTripPreservesShanghaiLocalDateTime(String driverMode) throws Exception {
        String jdbcUrl = driverUrl(driverMode);
        LocalDateTime expected = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        try (var connection = DriverManager.getConnection(
                        jdbcUrl, dataSource.getUsername(), dataSource.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+08:00'");
            statement.execute("""
                    CREATE TEMPORARY TABLE tmp_audit_timestamp_round_trip
                    (value DATETIME(3) NOT NULL)
                    """);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO tmp_audit_timestamp_round_trip (value) VALUES (?)")) {
                insert.setTimestamp(1, Timestamp.valueOf(expected));
                insert.executeUpdate();
            }
            try (var query = connection.prepareStatement(
                            "SELECT value FROM tmp_audit_timestamp_round_trip");
                    var resultSet = query.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getTimestamp(1).toLocalDateTime()).isEqualTo(expected);
            }
            statement.execute("DROP TEMPORARY TABLE tmp_audit_timestamp_round_trip");
        }
    }

    private String driverUrl(String driverMode) {
        String url = dataSource.getJdbcUrl().replaceAll(
                "([?&])(?:serverTimezone|connectionTimeZone)=[^&]*&?", "$1");
        if ("LOCAL".equals(driverMode)) {
            return url + (url.contains("?") ? "&" : "?") + "connectionTimeZone=LOCAL";
        }
        return url;
    }
}
