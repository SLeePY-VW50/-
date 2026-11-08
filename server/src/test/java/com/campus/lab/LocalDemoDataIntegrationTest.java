package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.campus.lab.auth.mapper.UserMapper;
import com.campus.lab.config.LocalDemoData;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalDemoDataIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LocalDemoData localDemoData;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void repeatedInitializationKeepsCountsAndExistingPasswordHashes() throws Exception {
        Map<String, Object> before = demoSnapshot();

        localDemoData.run(new DefaultApplicationArguments());
        localDemoData.run(new DefaultApplicationArguments());

        assertThat(demoSnapshot()).isEqualTo(before);
    }

    @Test
    void staleUserExistenceReadCannotCauseDuplicateInsertFailure() {
        UserMapper staleReadMapper = (UserMapper) Proxy.newProxyInstance(
                UserMapper.class.getClassLoader(),
                new Class<?>[] {UserMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("selectCount")) {
                        return 0L;
                    }
                    return method.invoke(userMapper, args);
                });
        LocalDemoData initializer = new LocalDemoData(staleReadMapper, passwordEncoder, jdbcTemplate);

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void localCheckinLaboratoryAllowsSameDayTestingWithoutChangingNormalLabs() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_lab WHERE lab_code = 'LAB-CHECKIN-DEMO'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_lab_seat s JOIN t_lab l ON l.lab_id = s.lab_id
                WHERE l.lab_code = 'LAB-CHECKIN-DEMO' AND s.seat_status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule r JOIN t_lab l ON l.lab_id = r.lab_id
                WHERE l.lab_code = 'LAB-CHECKIN-DEMO' AND r.open_time = '00:00:00'
                  AND r.close_time = '24:00:00' AND r.advance_min_days = 0 AND r.advance_max_days = 14
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule r JOIN t_lab l ON l.lab_id = r.lab_id
                WHERE l.lab_code = 'LAB-A' AND r.open_time = '08:00:00' AND r.close_time = '22:00:00'
                  AND r.advance_min_days IS NULL
                """, Integer.class)).isEqualTo(7);
    }

    @Test
    void repeatedInitializationKeepsTerminalHashAndSingleRow() throws Exception {
        String initialHash = terminalHash();

        localDemoData.run(new DefaultApplicationArguments());
        String hashAfterFirstRun = terminalHash();
        localDemoData.run(new DefaultApplicationArguments());

        assertThat(hashAfterFirstRun).isEqualTo(initialHash);
        assertThat(terminalHash()).isEqualTo(initialHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_device WHERE terminal_code = 'LAB-A-TERM'", Integer.class))
                .isEqualTo(1);
    }

    private String terminalHash() {
        return jdbcTemplate.queryForObject(
                "SELECT terminal_secret_hash FROM t_device WHERE terminal_code = 'LAB-A-TERM'",
                String.class);
    }

    private Map<String, Object> demoSnapshot() {
        List<Map<String, Object>> userHashes = jdbcTemplate.queryForList("""
                SELECT account, password_hash FROM sys_user
                WHERE account IN ('20240001', 'T1001', 'L1001', 'A1001') ORDER BY account
                """);
        return Map.of(
                "users", userHashes,
                "labs", count("t_lab"),
                "seats", count("t_lab_seat"),
                "rules", count("t_time_rule"),
                "terminalHash", jdbcTemplate.queryForObject(
                        "SELECT terminal_secret_hash FROM t_device WHERE terminal_code = 'LAB-A-TERM'",
                        String.class));
    }

    private int count(String tableName) {
        return switch (tableName) {
            case "t_lab", "t_lab_seat", "t_time_rule" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName, Integer.class);
            default -> throw new IllegalArgumentException("不支持的表演示计数");
        };
    }
}
