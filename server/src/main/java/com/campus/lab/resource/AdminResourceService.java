package com.campus.lab.resource;

import com.campus.lab.common.BusinessException;
import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.resource.dto.CreateLabRequest;
import com.campus.lab.resource.dto.CreateTerminalRequest;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.resource.dto.SeatView;
import com.campus.lab.resource.dto.TerminalView;
import com.campus.lab.resource.dto.UpdateTimeRuleRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminResourceService {

    private final JdbcTemplate jdbcTemplate;
    private final RuleValidator ruleValidator;
    private final PasswordEncoder passwordEncoder;

    public AdminResourceService(
            JdbcTemplate jdbcTemplate,
            RuleValidator ruleValidator,
            PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.ruleValidator = ruleValidator;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public LabView createLab(CreateLabRequest request) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_lab (lab_code, name, location, capacity, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, request.code(), request.name(), request.location(), request.capacity());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "实验室编码已存在");
        }
        return labByCode(request.code());
    }

    @Transactional
    public LabView updateLabStatus(long labId, ResourceStatus status) {
        requireLab(labId);
        jdbcTemplate.update("UPDATE t_lab SET status = ? WHERE lab_id = ?", status.name(), labId);
        return requireLab(labId);
    }

    @Transactional
    public SeatView updateSeatStatus(long seatId, ResourceStatus status) {
        requireSeat(seatId);
        jdbcTemplate.update(
                "UPDATE t_lab_seat SET seat_status = ? WHERE seat_id = ?", status.name(), seatId);
        return requireSeat(seatId);
    }

    @Transactional
    public com.campus.lab.resource.dto.TimeRuleView updateRule(
            long labId, long ruleId, UpdateTimeRuleRequest request) {
        TimeRule existing = requireRule(labId, ruleId);
        java.time.LocalTime openTime;
        java.time.LocalTime closeTime;
        try {
            openTime = DayTimes.parse(request.openTime(), false);
            closeTime = DayTimes.parse(request.closeTime(), true);
            if (DayTimes.minuteOfDay(closeTime, true) <= DayTimes.minuteOfDay(openTime, false)) {
                throw new IllegalArgumentException("关闭时间必须晚于开放时间");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        TimeRule candidate = new TimeRule(
                existing.ruleId(), existing.labId(), existing.dayOfWeek(), openTime, closeTime,
                request.minDurationMinutes(), request.maxDurationMinutes(),
                request.advanceMinDays(), request.advanceMaxDays());
        ReservationRuleConfig global;
        EffectiveTimeRule effective;
        try {
            global = loadReservationRuleConfig();
            effective = ruleValidator.merge(candidate, global);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (effective.advanceMinDays() > effective.advanceMaxDays()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "最少提前天数不能大于最多提前天数");
        }
        jdbcTemplate.update("""
                UPDATE t_time_rule
                SET open_time = ?, close_time = ?, min_duration_minutes = ?,
                    max_duration_minutes = ?, advance_min_days = ?, advance_max_days = ?
                WHERE rule_id = ? AND lab_id = ?
                """, DayTimes.format(openTime, false), DayTimes.format(closeTime, true),
                request.minDurationMinutes(), request.maxDurationMinutes(),
                request.advanceMinDays(), request.advanceMaxDays(), ruleId, labId);
        return com.campus.lab.resource.dto.TimeRuleView.from(
                ruleValidator.merge(requireRule(labId, ruleId), global));
    }

    @Transactional
    public TerminalView createTerminal(CreateTerminalRequest request) {
        int secretByteLength = request.initialSecret()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (secretByteLength > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "终端初始秘密不能超过72个UTF-8字节");
        }
        requireLab(request.labId());
        String secretHash = passwordEncoder.encode(request.initialSecret());
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_device
                        (lab_id, device_type, device_name, model, status,
                         terminal_code, terminal_secret_hash)
                    VALUES (?, 'CHECKIN_TERMINAL', ?, ?, 'ACTIVE', ?, ?)
                    """, request.labId(), request.deviceName(), request.model(),
                    request.terminalCode(), secretHash);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "终端编码已存在");
        }
        return terminalByCode(request.terminalCode());
    }

    private LabView requireLab(long labId) {
        List<LabView> labs = jdbcTemplate.query("""
                SELECT lab_id, lab_code, name, location, capacity, status
                FROM t_lab WHERE lab_id = ?
                """, (rs, rowNum) -> mapLab(rs), labId);
        if (labs.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
        return labs.getFirst();
    }

    private LabView labByCode(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT lab_id, lab_code, name, location, capacity, status
                FROM t_lab WHERE lab_code = ?
                """, (rs, rowNum) -> mapLab(rs), code);
    }

    private LabView mapLab(ResultSet rs) throws SQLException {
        return new LabView(
                rs.getLong("lab_id"), rs.getString("lab_code"), rs.getString("name"),
                rs.getString("location"), rs.getInt("capacity"),
                ResourceStatus.valueOf(rs.getString("status")));
    }

    private SeatView requireSeat(long seatId) {
        List<SeatView> seats = jdbcTemplate.query("""
                SELECT seat_id, lab_id, seat_code, zone_code, seat_index,
                       spec_level, near_window, seat_status
                FROM t_lab_seat WHERE seat_id = ?
                """, (rs, rowNum) -> new SeatView(
                rs.getLong("seat_id"), rs.getLong("lab_id"), rs.getString("seat_code"),
                rs.getString("zone_code"), rs.getInt("seat_index"), rs.getString("spec_level"),
                rs.getBoolean("near_window"), ResourceStatus.valueOf(rs.getString("seat_status"))),
                seatId);
        if (seats.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "工位不存在");
        }
        return seats.getFirst();
    }

    private TimeRule requireRule(long labId, long ruleId) {
        List<TimeRule> rules = jdbcTemplate.query("""
                SELECT rule_id, lab_id, day_of_week, open_time, close_time,
                       min_duration_minutes, max_duration_minutes,
                       advance_min_days, advance_max_days
                FROM t_time_rule WHERE lab_id = ? AND rule_id = ?
                """, (rs, rowNum) -> new TimeRule(
                rs.getLong("rule_id"), rs.getLong("lab_id"), rs.getInt("day_of_week"),
                DayTimes.parseDatabaseTime(rs.getString("open_time")),
                DayTimes.parseDatabaseTime(rs.getString("close_time")),
                rs.getObject("min_duration_minutes", Integer.class),
                rs.getObject("max_duration_minutes", Integer.class),
                rs.getObject("advance_min_days", Integer.class),
                rs.getObject("advance_max_days", Integer.class)), labId, ruleId);
        if (rules.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室时间规则不存在");
        }
        return rules.getFirst();
    }

    private TerminalView terminalByCode(String terminalCode) {
        return jdbcTemplate.queryForObject("""
                SELECT device_id, lab_id, device_name, model, terminal_code, status
                FROM t_device WHERE terminal_code = ? AND device_type = 'CHECKIN_TERMINAL'
                """, (rs, rowNum) -> new TerminalView(
                rs.getLong("device_id"), rs.getLong("lab_id"), rs.getString("device_name"),
                rs.getString("model"), rs.getString("terminal_code"),
                ResourceStatus.valueOf(rs.getString("status"))), terminalCode);
    }

    private ReservationRuleConfig loadReservationRuleConfig() {
        Map<String, String> config = jdbcTemplate.query("""
                SELECT config_key, config_value FROM t_sys_config
                WHERE config_key IN (
                    'reservation.duration.min.minutes',
                    'reservation.duration.max.minutes',
                    'reservation.advance.min.days',
                    'reservation.advance.max.days')
                """, rs -> {
            Map<String, String> values = new HashMap<>();
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
            return values;
        });
        return new ReservationRuleConfig(
                requiredInt(config, "reservation.duration.min.minutes"),
                requiredInt(config, "reservation.duration.max.minutes"),
                requiredInt(config, "reservation.advance.min.days"),
                requiredInt(config, "reservation.advance.max.days"));
    }

    private int requiredInt(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null) {
            throw new IllegalStateException("缺少预约规则配置: " + key);
        }
        return Integer.parseInt(value);
    }
}
