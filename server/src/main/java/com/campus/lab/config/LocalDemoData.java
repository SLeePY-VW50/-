package com.campus.lab.config;

import com.campus.lab.auth.entity.UserEntity;
import com.campus.lab.auth.entity.UserStatus;
import com.campus.lab.auth.mapper.UserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalDemoData implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public LocalDemoData(UserMapper userMapper, PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createUser("20240001", "演示学生", "STUDENT");
        createUser("T1001", "演示教师", "TEACHER");
        createUser("L1001", "实验室管理员", "LAB_ADMIN");
        createUser("A1001", "系统管理员", "SYS_ADMIN");

        long firstLabId = ensureLab("LAB-A", "人工智能实验室", "创新楼 A301", 20);
        long secondLabId = ensureLab("LAB-B", "软件开发实验室", "创新楼 B402", 24);
        createSeats(firstLabId, "LAB-A");
        createSeats(secondLabId, "LAB-B");
        createTimeRules(firstLabId);
        createTimeRules(secondLabId);
        createTerminal(firstLabId);
        createCheckinDemoLaboratory();
    }

    private void createCheckinDemoLaboratory() {
        long labId = ensureLab("LAB-CHECKIN-DEMO", "签到联调实验室（本地）", "本地测试环境", 1);
        jdbcTemplate.update("""
                INSERT INTO t_lab_seat
                    (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                VALUES (?, 'CHECKIN-SEAT-01', 'TEST', 1, 'STANDARD', FALSE, 'ACTIVE')
                ON DUPLICATE KEY UPDATE seat_id = t_lab_seat.seat_id
                """, labId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO t_time_rule
                        (lab_id, day_of_week, open_time, close_time, advance_min_days, advance_max_days)
                    VALUES (?, ?, '00:00:00', '24:00:00', 0, 14)
                    ON DUPLICATE KEY UPDATE rule_id = t_time_rule.rule_id
                    """, labId, day);
        }
    }

    private void createUser(String account, String name, String roleCode) {
        UserEntity user = new UserEntity();
        user.setAccount(account);
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode("Lab@2026"));
        user.setCreditScore(100);
        user.setStatus(UserStatus.ACTIVE);
        userMapper.insertIfAbsent(user);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                SELECT u.user_id, r.role_id
                FROM sys_user u
                JOIN sys_role r ON r.role_code = ?
                WHERE u.account = ?
                ON DUPLICATE KEY UPDATE id = sys_user_role.id
                """, roleCode, account);
    }

    private long ensureLab(String code, String name, String location, int capacity) {
        jdbcTemplate.update("""
                INSERT INTO t_lab (lab_code, name, location, capacity, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE lab_id = t_lab.lab_id
                """, code, name, location, capacity);
        Long labId = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        if (labId == null) {
            throw new IllegalStateException("本地演示实验室创建失败");
        }
        return labId;
    }

    private void createSeats(long labId, String labCode) {
        String[] specificationLevels = {"STANDARD", "HIGH", "GPU"};
        for (int index = 1; index <= 20; index++) {
            String zoneCode = labCode + (index <= 10 ? "-NORTH" : "-SOUTH");
            String seatCode = labCode + "-SEAT-" + String.format("%02d", index);
            String specificationLevel = specificationLevels[(index - 1) % specificationLevels.length];
            boolean nearWindow = index <= 3 || index % 5 == 0;
            jdbcTemplate.update("""
                    INSERT INTO t_lab_seat
                        (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                    ON DUPLICATE KEY UPDATE seat_id = t_lab_seat.seat_id
                    """, labId, seatCode, zoneCode, index, specificationLevel, nearWindow);
        }
    }

    private void createTimeRules(long labId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO t_time_rule (lab_id, day_of_week, open_time, close_time)
                    VALUES (?, ?, '08:00:00', '22:00:00')
                    ON DUPLICATE KEY UPDATE rule_id = t_time_rule.rule_id
                    """, labId, day);
        }
    }

    private void createTerminal(long labId) {
        jdbcTemplate.update("""
                INSERT INTO t_device
                    (lab_id, device_type, device_name, model, status, terminal_code, terminal_secret_hash)
                VALUES (?, 'CHECKIN_TERMINAL', '实验室签到终端', 'LOCAL-DEMO', 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE device_id = t_device.device_id
                """, labId, "LAB-A-TERM", passwordEncoder.encode("Terminal@2026"));
    }
}
