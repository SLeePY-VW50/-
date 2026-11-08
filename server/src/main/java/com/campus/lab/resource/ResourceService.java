package com.campus.lab.resource;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.resource.dto.CancellationRuleView;
import com.campus.lab.resource.dto.CheckinRuleView;
import com.campus.lab.resource.dto.LabAvailabilityView;
import com.campus.lab.resource.dto.LabRulesView;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.resource.dto.SeatAvailabilityView;
import com.campus.lab.resource.dto.SlotAvailabilityView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {

    private final JdbcTemplate jdbcTemplate;
    private final RuleValidator ruleValidator;

    public ResourceService(JdbcTemplate jdbcTemplate, RuleValidator ruleValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.ruleValidator = ruleValidator;
    }

    public List<LabView> listLabs() {
        return jdbcTemplate.query("""
                SELECT lab_id, lab_code, name, location, capacity, status
                FROM t_lab
                ORDER BY lab_id
                """, this::mapLab);
    }

    public LabRulesView getRules(long labId) {
        requireLab(labId);
        Map<String, String> config = loadPublicConfig();
        ReservationRuleConfig global = new ReservationRuleConfig(
                requiredInt(config, "reservation.duration.min.minutes"),
                requiredInt(config, "reservation.duration.max.minutes"),
                requiredInt(config, "reservation.advance.min.days"),
                requiredInt(config, "reservation.advance.max.days"));
        List<EffectiveTimeRule> rules = jdbcTemplate.query("""
                SELECT rule_id, lab_id, day_of_week, open_time, close_time,
                       min_duration_minutes, max_duration_minutes,
                       advance_min_days, advance_max_days
                FROM t_time_rule
                WHERE lab_id = ?
                ORDER BY day_of_week
                """, (rs, rowNum) -> ruleValidator.merge(mapTimeRule(rs), global), labId);
        CancellationRuleView cancellation = new CancellationRuleView(
                requiredInt(config, "cancel.free.before.hours"));
        CheckinRuleView checkin = new CheckinRuleView(
                requiredInt(config, "checkin.before.minutes"),
                requiredInt(config, "checkin.grace.minutes"),
                requiredInt(config, "checkin.choice.expire.seconds"),
                requiredInt(config, "checkin.max.failed.attempts"),
                requiredInt(config, "checkin.lockout.minutes"));
        return new LabRulesView(labId, rules, cancellation, checkin);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LabAvailabilityView getAvailability(long labId, LocalDate date) {
        LabView laboratory = requireLab(labId);
        Long roomMask = jdbcTemplate.queryForObject("""
                SELECT COALESCE(b.mask, 0)
                FROM t_lab l
                LEFT JOIN t_slot_bitmap b
                  ON b.lab_id = l.lab_id AND b.bitmap_date = ?
                WHERE l.lab_id = ?
                """, Long.class, date, labId);
        long resolvedRoomMask = roomMask == null ? 0 : roomMask;
        List<SeatSnapshot> seats = jdbcTemplate.query("""
                SELECT s.seat_id, s.seat_code, s.zone_code, s.seat_index,
                       s.spec_level, s.near_window, s.seat_status,
                       COALESCE(b.mask, 0) AS mask
                FROM t_lab_seat s
                LEFT JOIN t_seat_bitmap b
                  ON b.seat_id = s.seat_id AND b.bitmap_date = ?
                WHERE s.lab_id = ?
                ORDER BY s.seat_index
                """, (rs, rowNum) -> new SeatSnapshot(
                rs.getLong("seat_id"),
                rs.getString("seat_code"),
                rs.getString("zone_code"),
                rs.getInt("seat_index"),
                rs.getString("spec_level"),
                rs.getBoolean("near_window"),
                ResourceStatus.valueOf(rs.getString("seat_status")),
                rs.getLong("mask")), date, labId);
        List<SeatAvailabilityView> seatViews = seats.stream()
                .map(seat -> new SeatAvailabilityView(
                        seat.seatId(), seat.seatCode(), seat.zoneCode(), seat.seatIndex(),
                        seat.specLevel(), seat.nearWindow(), seat.status(),
                        Long.toUnsignedString(seat.mask())))
                .toList();
        List<SlotAvailabilityView> slots = buildSlots(
                resolvedRoomMask, seats, laboratory.status());
        return new LabAvailabilityView(
                labId, date, laboratory.status(), Long.toUnsignedString(resolvedRoomMask),
                seatViews, slots);
    }

    private List<SlotAvailabilityView> buildSlots(
            long roomMask, List<SeatSnapshot> seats, ResourceStatus labStatus) {
        List<SlotAvailabilityView> slots = new ArrayList<>(TimeSlotMask.SLOTS_PER_DAY);
        boolean labActive = labStatus == ResourceStatus.ACTIVE;
        for (int index = 0; index < TimeSlotMask.SLOTS_PER_DAY; index++) {
            long slotBit = 1L << index;
            boolean roomOccupied = (roomMask & slotBit) != 0;
            int occupiedSeatCount = 0;
            int availableSeatCount = 0;
            for (SeatSnapshot seat : seats) {
                boolean occupied = (seat.mask() & slotBit) != 0;
                if (occupied) {
                    occupiedSeatCount++;
                }
                if (labActive && !roomOccupied && !occupied
                        && seat.status() == ResourceStatus.ACTIVE) {
                    availableSeatCount++;
                }
            }
            slots.add(new SlotAvailabilityView(
                    index, slotTime(index), slotTime(index + 1), roomOccupied,
                    occupiedSeatCount, availableSeatCount));
        }
        return slots;
    }

    public LabView getLab(long labId) {
        return requireLab(labId);
    }

    private LabView requireLab(long labId) {
        List<LabView> labs = jdbcTemplate.query("""
                SELECT lab_id, lab_code, name, location, capacity, status
                FROM t_lab WHERE lab_id = ?
                """, this::mapLab, labId);
        if (labs.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
        return labs.getFirst();
    }

    private LabView mapLab(ResultSet rs, int rowNum) throws SQLException {
        return new LabView(
                rs.getLong("lab_id"), rs.getString("lab_code"), rs.getString("name"),
                rs.getString("location"), rs.getInt("capacity"),
                ResourceStatus.valueOf(rs.getString("status")));
    }

    private TimeRule mapTimeRule(ResultSet rs) throws SQLException {
        return new TimeRule(
                rs.getLong("rule_id"), rs.getLong("lab_id"), rs.getInt("day_of_week"),
                com.campus.lab.common.DayTimes.parseDatabaseTime(rs.getString("open_time")),
                com.campus.lab.common.DayTimes.parseDatabaseTime(rs.getString("close_time")),
                rs.getObject("min_duration_minutes", Integer.class),
                rs.getObject("max_duration_minutes", Integer.class),
                rs.getObject("advance_min_days", Integer.class),
                rs.getObject("advance_max_days", Integer.class));
    }

    private Map<String, String> loadPublicConfig() {
        return jdbcTemplate.query("""
                SELECT config_key, config_value FROM t_sys_config
                WHERE config_key IN (
                    'reservation.duration.min.minutes',
                    'reservation.duration.max.minutes',
                    'reservation.advance.min.days',
                    'reservation.advance.max.days',
                    'cancel.free.before.hours',
                    'checkin.before.minutes',
                    'checkin.grace.minutes',
                    'checkin.choice.expire.seconds',
                    'checkin.max.failed.attempts',
                    'checkin.lockout.minutes')
                """, rs -> {
            Map<String, String> values = new HashMap<>();
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
            return values;
        });
    }

    private int requiredInt(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null) {
            throw new IllegalStateException("缺少公开规则配置: " + key);
        }
        return Integer.parseInt(value);
    }

    private static String slotTime(int slot) {
        if (slot == TimeSlotMask.SLOTS_PER_DAY) {
            return "24:00";
        }
        return String.format(Locale.ROOT, "%02d:%02d", slot / 2, (slot % 2) * 30);
    }
}
