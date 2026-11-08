package com.campus.lab.statistics;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class StatisticsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final long maxAssignmentRows;

    public StatisticsRepository(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.statistics.max-assignment-rows:50000}") long maxAssignmentRows) {
        if (maxAssignmentRows < 1) {
            throw new IllegalArgumentException("统计分配行上限必须大于零");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.maxAssignmentRows = maxAssignmentRows;
    }

    public Facts loadFacts(Long labId, Long ownerId, LocalDate start, LocalDate end) {
        if (countAssignmentRows(labId, ownerId, start, end) > maxAssignmentRows) {
            throw new com.campus.lab.common.BusinessException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "统计分配行过多，请缩小实验室、用户或日期范围");
        }
        StringBuilder labSql = new StringBuilder("SELECT l.lab_id FROM t_lab l WHERE 1 = 1");
        List<Object> labArguments = new ArrayList<>();
        if (labId != null) {
            labSql.append(" AND l.lab_id = ?");
            labArguments.add(labId);
        }
        if (ownerId != null) {
            labSql.append("""
                     AND EXISTS (
                         SELECT 1 FROM t_reservation r
                         WHERE r.lab_id = l.lab_id AND r.user_id = ?
                           AND r.request_date BETWEEN ? AND ?)
                    """);
            labArguments.add(ownerId);
            labArguments.add(start);
            labArguments.add(end);
        }
        labSql.append(" ORDER BY l.lab_id");
        List<Long> labIds = jdbcTemplate.queryForList(
                labSql.toString(), Long.class, labArguments.toArray());
        if (labIds.isEmpty()) {
            return new Facts(List.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        }
        String placeholders = String.join(",", Collections.nCopies(labIds.size(), "?"));
        List<Object> seatArguments = new ArrayList<>(labIds);
        seatArguments.add(start);
        seatArguments.add(end);
        Map<Long, List<SeatRow>> seats = jdbcTemplate.query("""
                SELECT s.lab_id, s.seat_id, s.seat_status FROM t_lab_seat s
                WHERE s.lab_id IN (""" + placeholders + """
                )
                  AND (s.seat_status = 'ACTIVE' OR EXISTS (
                      SELECT 1 FROM t_reservation_seat rs
                      JOIN t_reservation r ON r.resv_id = rs.resv_id
                      WHERE rs.seat_id = s.seat_id AND r.request_date BETWEEN ? AND ?))
                ORDER BY s.lab_id, s.seat_id
                """, rs -> {
                    Map<Long, List<SeatRow>> values = new HashMap<>();
                    while (rs.next()) {
                        long lab = rs.getLong("lab_id");
                        values.computeIfAbsent(lab, key -> new ArrayList<>())
                                .add(new SeatRow(lab, rs.getLong("seat_id"),
                                        "ACTIVE".equals(rs.getString("seat_status"))));
                    }
                    return values;
                }, seatArguments.toArray());
        Map<LabDay, RuleRow> rules = jdbcTemplate.query("""
                SELECT lab_id, day_of_week, open_time, close_time FROM t_time_rule
                WHERE lab_id IN (""" + placeholders + ") ORDER BY lab_id, day_of_week", rs -> {
                    Map<LabDay, RuleRow> values = new HashMap<>();
                    while (rs.next()) {
                        long lab = rs.getLong("lab_id");
                        int day = rs.getInt("day_of_week");
                        values.put(new LabDay(lab, day), new RuleRow(
                                lab, day, rs.getString("open_time"), rs.getString("close_time")));
                    }
                    return values;
                }, labIds.toArray());

        StringBuilder reservationSql = new StringBuilder("""
                SELECT r.resv_id, r.user_id, r.lab_id, r.request_date, r.reservation_mode,
                       r.start_time, r.end_time, r.checkin_time, r.checkout_time, r.status
                FROM t_reservation r
                WHERE r.request_date BETWEEN ? AND ?
                """);
        List<Object> reservationArguments = new ArrayList<>();
        reservationArguments.add(start);
        reservationArguments.add(end);
        reservationSql.append(" AND r.lab_id IN (").append(placeholders).append(")");
        reservationArguments.addAll(labIds);
        if (ownerId != null) {
            reservationSql.append(" AND r.user_id = ?");
            reservationArguments.add(ownerId);
        }
        reservationSql.append(" ORDER BY r.request_date, r.lab_id, r.resv_id");
        List<ReservationRow> reservations = jdbcTemplate.query(
                reservationSql.toString(), this::mapReservation, reservationArguments.toArray());

        StringBuilder assignmentSql = new StringBuilder("""
                SELECT r.resv_id, r.lab_id, r.request_date, rs.seat_id, rs.time_mask
                FROM t_reservation r
                JOIN t_reservation_seat rs ON rs.resv_id = r.resv_id
                WHERE r.request_date BETWEEN ? AND ?
                """);
        List<Object> assignmentArguments = new ArrayList<>();
        assignmentArguments.add(start);
        assignmentArguments.add(end);
        assignmentSql.append(" AND r.lab_id IN (").append(placeholders).append(")");
        assignmentArguments.addAll(labIds);
        assignmentSql.append(" ORDER BY r.resv_id, rs.seat_id");
        Map<Long, List<AssignmentRow>> assignments = jdbcTemplate.query(assignmentSql.toString(), rs -> {
            Map<Long, List<AssignmentRow>> values = new HashMap<>();
            while (rs.next()) {
                values.computeIfAbsent(rs.getLong("resv_id"), key -> new ArrayList<>())
                        .add(new AssignmentRow(
                                rs.getLong("resv_id"), rs.getLong("lab_id"),
                                rs.getObject("request_date", LocalDate.class),
                                rs.getLong("seat_id"), rs.getLong("time_mask")));
            }
            return values;
        }, assignmentArguments.toArray());
        List<Object> baselineArguments = new ArrayList<>();
        baselineArguments.add(start);
        baselineArguments.add(end);
        baselineArguments.addAll(labIds);
        Map<LabStatDate, Long> baselines = jdbcTemplate.query("""
                SELECT lab_id, stat_date, available_seat_minutes FROM t_stat_daily
                WHERE stat_date BETWEEN ? AND ? AND lab_id IN (""" + placeholders + ")", rs -> {
                    Map<LabStatDate, Long> values = new HashMap<>();
                    while (rs.next()) {
                        long available = rs.getLong("available_seat_minutes");
                        if (!rs.wasNull()) {
                            values.put(new LabStatDate(
                                    rs.getLong("lab_id"), rs.getObject("stat_date", LocalDate.class)),
                                    available);
                        }
                    }
                    return values;
                }, baselineArguments.toArray());
        return new Facts(labIds, seats, rules, reservations, assignments, baselines);
    }

    public long countAssignmentRows(Long labId, Long ownerId, LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id
                WHERE r.request_date BETWEEN ? AND ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(start);
        arguments.add(end);
        if (labId != null) {
            sql.append(" AND r.lab_id = ?");
            arguments.add(labId);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    public Map<LocalDate, CountRow> countReservations(
            Long ownerId, LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder("""
                SELECT request_date, COUNT(*) AS reservation_count,
                       COALESCE(SUM(status = 'NO_SHOW'), 0) AS no_show_count,
                       COALESCE(SUM(status = 'PENDING_SCHEDULE'), 0) AS pending_count,
                       COALESCE(SUM(status = 'IN_USE'), 0) AS in_use_count,
                       COALESCE(SUM(status = 'APPROVED'), 0) AS approved_count,
                       COALESCE(SUM(status = 'COMPLETED'), 0) AS completed_count,
                       COALESCE(SUM(status = 'CANCELLED'), 0) AS cancelled_count
                FROM t_reservation WHERE request_date BETWEEN ? AND ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(start);
        arguments.add(end);
        if (ownerId != null) {
            sql.append(" AND user_id = ?");
            arguments.add(ownerId);
        }
        sql.append(" GROUP BY request_date ORDER BY request_date");
        return jdbcTemplate.query(sql.toString(), rs -> {
            Map<LocalDate, CountRow> values = new HashMap<>();
            while (rs.next()) {
                values.put(rs.getObject("request_date", LocalDate.class), new CountRow(
                        rs.getLong("reservation_count"), rs.getLong("no_show_count"),
                        rs.getLong("pending_count"), rs.getLong("in_use_count"),
                        rs.getLong("approved_count"), rs.getLong("completed_count"),
                        rs.getLong("cancelled_count")));
            }
            return values;
        }, arguments.toArray());
    }

    public long countLabs(Long ownerId, LocalDate date) {
        if (ownerId == null) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_lab", Long.class);
            return count == null ? 0 : count;
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT lab_id) FROM t_reservation
                WHERE user_id = ? AND request_date = ? AND lab_id IS NOT NULL
                """, Long.class, ownerId, date);
        return count == null ? 0 : count;
    }

    public int creditScore(long userId) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
        if (score == null) {
            throw new com.campus.lab.common.BusinessException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "用户不存在或不可用");
        }
        return score;
    }

    public List<Long> listAllLabIds() {
        return jdbcTemplate.queryForList("SELECT lab_id FROM t_lab ORDER BY lab_id", Long.class);
    }

    public List<StatisticsViews.LabDateTarget> listBackfillStarts(LocalDate yesterday) {
        return jdbcTemplate.query("""
                SELECT a.lab_id,
                       CASE
                           WHEN NOT EXISTS (
                               SELECT 1 FROM t_stat_daily s
                               WHERE s.lab_id = a.lab_id AND s.stat_date = a.activity_date)
                           THEN a.activity_date
                           ELSE (
                               SELECT DATE_ADD(s.stat_date, INTERVAL 1 DAY)
                               FROM t_stat_daily s
                               WHERE s.lab_id = a.lab_id
                                 AND DATE_ADD(s.stat_date, INTERVAL 1 DAY) < ?
                                 AND NOT EXISTS (
                                     SELECT 1 FROM t_stat_daily next_stat
                                     WHERE next_stat.lab_id = s.lab_id
                                       AND next_stat.stat_date = DATE_ADD(s.stat_date, INTERVAL 1 DAY))
                               ORDER BY s.stat_date
                               LIMIT 1)
                       END AS start_date
                FROM (
                    SELECT l.lab_id,
                           LEAST(
                               COALESCE((SELECT MIN(s.stat_date) FROM t_stat_daily s
                                         WHERE s.lab_id = l.lab_id), '9999-12-31'),
                               COALESCE((SELECT MIN(r.request_date) FROM t_reservation r
                                         WHERE r.lab_id = l.lab_id), '9999-12-31'))
                               AS activity_date
                    FROM t_lab l
                ) a
                HAVING start_date IS NOT NULL AND start_date < ?
                ORDER BY start_date, a.lab_id
                LIMIT 31
                """, (rs, rowNum) -> new StatisticsViews.LabDateTarget(
                        rs.getLong("lab_id"), rs.getObject("start_date", LocalDate.class)),
                yesterday, yesterday);
    }

    public void upsertDaily(StatisticsViews.DailyMetric metric) {
        jdbcTemplate.update(dailyUpsertSql(), metric.labId(), metric.date(),
                metric.plannedUtilization(), metric.actualUtilization(), metric.noShowRate(),
                metric.reservationCount(), metric.availableSeatMinutes());
    }

    public void upsertDailyBatch(List<StatisticsViews.DailyMetric> metrics) {
        List<Object[]> arguments = metrics.stream()
                .map(metric -> new Object[] {
                        metric.labId(), metric.date(), metric.plannedUtilization(),
                        metric.actualUtilization(), metric.noShowRate(), metric.reservationCount(),
                        metric.availableSeatMinutes()})
                .toList();
        jdbcTemplate.batchUpdate(dailyUpsertSql(), arguments);
    }

    private static String dailyUpsertSql() {
        return """
                INSERT INTO t_stat_daily
                    (lab_id, stat_date, planned_utilization, actual_utilization,
                     no_show_rate, reservation_count, available_seat_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    planned_utilization = VALUES(planned_utilization),
                    actual_utilization = VALUES(actual_utilization),
                    no_show_rate = VALUES(no_show_rate),
                    reservation_count = VALUES(reservation_count),
                    available_seat_minutes = COALESCE(
                        available_seat_minutes, VALUES(available_seat_minutes))
                """;
    }

    public List<CsvRow> listCsvRows(
            Long ownerId, Long labId, LocalDate start, LocalDate end, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.resv_id, r.user_id, u.account, r.lab_id, l.name AS lab_name,
                       r.request_date, r.reservation_mode, r.status, r.start_time, r.end_time,
                       r.attendee_count, r.seat_count, r.purpose,
                       r.checkin_time, r.checkout_time, r.created_at
                FROM t_reservation r
                JOIN sys_user u ON u.user_id = r.user_id
                LEFT JOIN t_lab l ON l.lab_id = r.lab_id
                WHERE r.request_date BETWEEN ? AND ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(start);
        arguments.add(end);
        if (ownerId != null) {
            sql.append(" AND r.user_id = ?");
            arguments.add(ownerId);
        }
        if (labId != null) {
            sql.append(" AND r.lab_id = ?");
            arguments.add(labId);
        }
        sql.append(" ORDER BY r.request_date, r.resv_id LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new CsvRow(
                rs.getLong("resv_id"), rs.getLong("user_id"), rs.getString("account"),
                rs.getObject("lab_id", Long.class), rs.getString("lab_name"),
                rs.getObject("request_date", LocalDate.class), rs.getString("reservation_mode"),
                rs.getString("status"), rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class), rs.getInt("attendee_count"),
                rs.getObject("seat_count", Integer.class), rs.getString("purpose"),
                rs.getObject("checkin_time", LocalDateTime.class),
                rs.getObject("checkout_time", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class)), arguments.toArray());
    }

    private ReservationRow mapReservation(ResultSet rs, int rowNum) throws SQLException {
        return new ReservationRow(
                rs.getLong("resv_id"), rs.getLong("user_id"), rs.getLong("lab_id"),
                rs.getObject("request_date", LocalDate.class),
                ReservationMode.valueOf(rs.getString("reservation_mode")),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class),
                rs.getObject("checkin_time", LocalDateTime.class),
                rs.getObject("checkout_time", LocalDateTime.class),
                ReservationStatus.valueOf(rs.getString("status")));
    }

    public record Facts(
            List<Long> labIds, Map<Long, List<SeatRow>> seats, Map<LabDay, RuleRow> rules,
            List<ReservationRow> reservations, Map<Long, List<AssignmentRow>> assignments,
            Map<LabStatDate, Long> dailyBaselines) {
    }

    public record LabDay(long labId, int dayOfWeek) {
    }

    public record LabStatDate(long labId, LocalDate date) {
    }

    public record SeatRow(long labId, long seatId, boolean active) {
    }

    public record RuleRow(long labId, int dayOfWeek, String openTime, String closeTime) {
    }

    public record ReservationRow(
            long reservationId, long userId, long labId, LocalDate date, ReservationMode mode,
            LocalDateTime startTime, LocalDateTime endTime, LocalDateTime checkinTime,
            LocalDateTime checkoutTime, ReservationStatus status) {
    }

    public record AssignmentRow(
            long reservationId, long labId, LocalDate date, long seatId, long mask) {
    }

    public record CountRow(
            long reservationCount, long noShowCount, long pendingScheduleCount, long inUseCount,
            long approvedCount, long completedCount, long cancelledCount) {
        public static CountRow empty() {
            return new CountRow(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record CsvRow(
            long reservationId, long userId, String account, Long labId, String labName,
            LocalDate date, String mode, String status, LocalDateTime startTime,
            LocalDateTime endTime, int attendeeCount, Integer seatCount, String purpose,
            LocalDateTime checkinTime, LocalDateTime checkoutTime, LocalDateTime createdAt) {
    }
}
