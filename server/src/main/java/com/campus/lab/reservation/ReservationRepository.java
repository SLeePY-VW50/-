package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.ReservationSeatView;
import com.campus.lab.reservation.dto.ReservationView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class ReservationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReservationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UserAdmission lockUser(long userId) {
        List<UserAdmission> users = jdbcTemplate.query("""
                SELECT status, credit_score FROM sys_user WHERE user_id = ? FOR UPDATE
                """, (rs, rowNum) -> new UserAdmission(rs.getString(1), rs.getInt(2)), userId);
        if (users.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或不可用");
        }
        return users.getFirst();
    }

    public int creditThreshold() {
        String value = jdbcTemplate.queryForObject(
                "SELECT config_value FROM t_sys_config WHERE config_key = 'credit.threshold'",
                String.class);
        if (value == null) {
            throw new IllegalStateException("缺少预约信用阈值配置");
        }
        return Integer.parseInt(value);
    }

    public boolean hasEffectiveOverlap(long userId, NormalizedReservation request) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation
                WHERE user_id = ? AND request_date = ?
                  AND status IN ('PENDING_SCHEDULE', 'APPROVED', 'IN_USE')
                  AND TIME_TO_SEC(request_window_start) < ?
                  AND TIME_TO_SEC(request_window_end) > ?
                """, Integer.class, userId, request.date(), endSecond(request.endTime()),
                startSecond(request.startTime()));
        return count != null && count > 0;
    }

    public LabDetails requireLab(long labId) {
        List<LabDetails> labs = jdbcTemplate.query("""
                SELECT lab_id, name, capacity, status FROM t_lab WHERE lab_id = ?
                """, (rs, rowNum) -> new LabDetails(
                        rs.getLong("lab_id"), rs.getString("name"), rs.getInt("capacity"),
                        ResourceStatus.valueOf(rs.getString("status"))), labId);
        if (labs.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
        return labs.getFirst();
    }

    public LabDetails lockLabForUpdate(long labId) {
        List<LabDetails> labs = jdbcTemplate.query("""
                SELECT lab_id, name, capacity, status FROM t_lab WHERE lab_id = ? FOR UPDATE
                """, (rs, rowNum) -> new LabDetails(
                        rs.getLong("lab_id"), rs.getString("name"), rs.getInt("capacity"),
                        ResourceStatus.valueOf(rs.getString("status"))), labId);
        if (labs.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
        return labs.getFirst();
    }

    public List<Long> listActiveLabIds() {
        return jdbcTemplate.queryForList(
                "SELECT lab_id FROM t_lab WHERE status = 'ACTIVE' ORDER BY lab_id", Long.class);
    }

    public void ensureLabBitmap(long labId, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask, version)
                VALUES (?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE bitmap_id = t_slot_bitmap.bitmap_id
                """, labId, date);
    }

    public LabBitmap lockLabBitmap(long labId, LocalDate date) {
        return jdbcTemplate.queryForObject("""
                SELECT mask, version FROM t_slot_bitmap
                WHERE lab_id = ? AND bitmap_date = ? FOR UPDATE
                """, (rs, rowNum) -> new LabBitmap(rs.getLong("mask"), rs.getLong("version")),
                labId, date);
    }

    public void ensureSeatBitmaps(long labId, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask, version)
                SELECT seat_id, ?, 0, 0 FROM t_lab_seat WHERE lab_id = ?
                ON DUPLICATE KEY UPDATE bitmap_id = t_seat_bitmap.bitmap_id
                """, date, labId);
    }

    public List<SeatBitmap> loadSeatBitmaps(long labId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT s.seat_id, s.seat_code, s.zone_code, s.seat_index,
                       s.spec_level, s.near_window, s.seat_status, b.mask, b.version
                FROM t_lab_seat s
                JOIN t_seat_bitmap b ON b.seat_id = s.seat_id AND b.bitmap_date = ?
                WHERE s.lab_id = ?
                ORDER BY s.seat_index, s.seat_id
                FOR UPDATE
                """, (rs, rowNum) -> new SeatBitmap(
                        new SeatSnapshot(
                                rs.getLong("seat_id"), rs.getString("seat_code"),
                                rs.getString("zone_code"), rs.getInt("seat_index"),
                                rs.getString("spec_level"), rs.getBoolean("near_window"),
                                ResourceStatus.valueOf(rs.getString("seat_status")), rs.getLong("mask")),
                        rs.getLong("version")), date, labId);
    }

    public long insertBatch(
            long userId, long labId, String batchNo, String courseName, String purpose) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_batch (batch_no, user_id, lab_id, course_name, purpose)
                VALUES (?, ?, ?, ?, ?)
                """, batchNo, userId, labId, courseName, purpose);
        Long batchId = jdbcTemplate.queryForObject(
                "SELECT batch_id FROM t_reservation_batch WHERE batch_no = ?", Long.class, batchNo);
        if (batchId == null) {
            throw new IllegalStateException("批次写入后未获得主键");
        }
        return batchId;
    }

    public ReservationBatchSummary findBatchSummary(long batchId) {
        List<ReservationBatchSummary> batches = jdbcTemplate.query("""
                SELECT b.user_id, b.batch_id, b.batch_no, b.course_name,
                       b.lab_id, l.name AS lab_name, b.purpose
                FROM t_reservation_batch b
                JOIN t_lab l ON l.lab_id = b.lab_id
                WHERE b.batch_id = ?
                """, (rs, rowNum) -> new ReservationBatchSummary(
                        rs.getLong("user_id"), rs.getLong("batch_id"), rs.getString("batch_no"),
                        rs.getString("course_name"), rs.getLong("lab_id"),
                        rs.getString("lab_name"), rs.getString("purpose")), batchId);
        return batches.isEmpty() ? null : batches.getFirst();
    }

    public List<Long> listReservationIdsByBatch(long batchId) {
        return jdbcTemplate.queryForList("""
                SELECT resv_id FROM t_reservation
                WHERE batch_id = ? ORDER BY request_date, start_time, resv_id
                """, Long.class, batchId);
    }

    public long insertReservation(
            long userId,
            Long batchId,
            NormalizedReservation request,
            Long finalLabId,
            LocalDateTime finalStart,
            LocalDateTime finalEnd,
            Integer finalSeatCount,
            ReservationStatus status) {
        String sql = """
                INSERT INTO t_reservation
                    (user_id, batch_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count,
                     purpose, preferences, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            setNullableLong(statement, 2, batchId);
            statement.setString(3, request.allocationMode().name());
            statement.setString(4, request.reservationMode().name());
            statement.setDate(5, java.sql.Date.valueOf(request.date()));
            statement.setString(6, requestTime(request.startTime(), false));
            statement.setString(7, requestTime(request.endTime(), true));
            statement.setInt(8, request.durationMinutes());
            setNullableLong(statement, 9, finalLabId);
            setNullableTimestamp(statement, 10, finalStart);
            setNullableTimestamp(statement, 11, finalEnd);
            statement.setInt(12, request.attendeeCount());
            setNullableInteger(statement, 13, finalSeatCount);
            statement.setString(14, request.purpose());
            statement.setString(15, preferencesJson(request));
            statement.setString(16, status.name());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("预约写入后未获得主键");
        }
        return key.longValue();
    }

    public void insertSeatAssignment(long reservationId, long seatId, long mask) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_seat (resv_id, seat_id, time_mask, assignment_method)
                VALUES (?, ?, ?, 'FIXED')
                """, reservationId, seatId, mask);
    }

    public void updateSeatMask(long seatId, LocalDate date, long expectedVersion, long requestMask) {
        int updated = jdbcTemplate.update("""
                UPDATE t_seat_bitmap
                SET mask = mask | ?, version = version + 1
                WHERE seat_id = ? AND bitmap_date = ?
                  AND version = ? AND (mask & ?) = 0
                """, requestMask, seatId, date, expectedVersion, requestMask);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "工位占用已变化，请重新选择");
        }
    }

    public void updateRoomMask(long labId, LocalDate date, long expectedVersion, long requestMask) {
        int updated = jdbcTemplate.update("""
                UPDATE t_slot_bitmap
                SET mask = mask | ?, version = version + 1
                WHERE lab_id = ? AND bitmap_date = ?
                  AND version = ? AND (mask & ?) = 0
                """, requestMask, labId, date, expectedVersion, requestMask);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "整室占用已变化，请重新选择");
        }
    }

    public CancellationTarget findCancellationTarget(long reservationId) {
        List<CancellationTarget> targets = jdbcTemplate.query("""
                SELECT lab_id, request_date FROM t_reservation WHERE resv_id = ?
                """, (rs, rowNum) -> new CancellationTarget(
                        rs.getObject("lab_id", Long.class),
                        rs.getObject("request_date", LocalDate.class)), reservationId);
        return targets.isEmpty() ? null : targets.getFirst();
    }

    public CancellationReservation lockReservationForCancellation(long reservationId) {
        List<CancellationReservation> reservations = jdbcTemplate.query("""
                SELECT resv_id, user_id, request_date, request_window_start,
                       lab_id, start_time, end_time, reservation_mode, seat_count, status
                FROM t_reservation WHERE resv_id = ? FOR UPDATE
                """, (rs, rowNum) -> new CancellationReservation(
                        rs.getLong("resv_id"), rs.getLong("user_id"),
                        rs.getObject("request_date", LocalDate.class),
                        rs.getTime("request_window_start").toLocalTime(),
                        rs.getObject("lab_id", Long.class),
                        rs.getObject("start_time", LocalDateTime.class),
                        rs.getObject("end_time", LocalDateTime.class),
                        ReservationMode.valueOf(rs.getString("reservation_mode")),
                        rs.getObject("seat_count", Integer.class),
                        ReservationStatus.valueOf(rs.getString("status"))), reservationId);
        return reservations.isEmpty() ? null : reservations.getFirst();
    }

    public List<AssignedSeatBitmap> lockAssignedSeatBitmaps(long reservationId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT rs.seat_id, rs.time_mask, b.mask, b.version
                FROM t_reservation_seat rs
                JOIN t_lab_seat s ON s.seat_id = rs.seat_id
                JOIN t_seat_bitmap b ON b.seat_id = rs.seat_id AND b.bitmap_date = ?
                WHERE rs.resv_id = ?
                ORDER BY s.seat_index, rs.seat_id
                FOR UPDATE
                """, (rs, rowNum) -> new AssignedSeatBitmap(
                        rs.getLong("seat_id"), rs.getLong("time_mask"),
                        rs.getLong("mask"), rs.getLong("version")), date, reservationId);
    }

    public void releaseSeatMask(long seatId, LocalDate date, long expectedVersion, long releaseMask) {
        int updated = jdbcTemplate.update("""
                UPDATE t_seat_bitmap
                SET mask = mask & ~?, version = version + 1
                WHERE seat_id = ? AND bitmap_date = ? AND version = ?
                  AND (mask & ?) = ?
                """, releaseMask, seatId, date, expectedVersion, releaseMask, releaseMask);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "工位占用已变化，无法安全释放");
        }
    }

    public void releaseRoomMask(long labId, LocalDate date, long expectedVersion, long releaseMask) {
        int updated = jdbcTemplate.update("""
                UPDATE t_slot_bitmap
                SET mask = mask & ~?, version = version + 1
                WHERE lab_id = ? AND bitmap_date = ? AND version = ?
                  AND (mask & ?) = ?
                """, releaseMask, labId, date, expectedVersion, releaseMask, releaseMask);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "整室占用已变化，无法安全释放");
        }
    }

    public int checkinReservation(
            long reservationId, long userId, long labId, LocalDateTime checkinTime,
            com.campus.lab.checkin.CheckinChannel channel) {
        return jdbcTemplate.update("""
                UPDATE t_reservation
                SET status = 'IN_USE', checkin_time = ?, checkin_channel = ?
                WHERE resv_id = ? AND user_id = ? AND lab_id = ? AND status = 'APPROVED'
                """, Timestamp.valueOf(checkinTime), channel.name(), reservationId, userId, labId);
    }

    public void insertCheckinLog(
            long reservationId, long operatorId, com.campus.lab.checkin.CheckinChannel channel) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, 'APPROVED', 'IN_USE', ?, ?)
                """, reservationId, operatorId, channel.logReason());
    }

    public int checkoutReservation(
            long reservationId, long userId, LocalDateTime checkoutTime,
            com.campus.lab.checkin.CheckoutChannel channel) {
        return jdbcTemplate.update("""
                UPDATE t_reservation
                SET status = 'COMPLETED', checkout_time = ?, checkout_channel = ?
                WHERE resv_id = ? AND user_id = ? AND status = 'IN_USE'
                """, Timestamp.valueOf(checkoutTime), channel.name(), reservationId, userId);
    }

    public void insertCheckoutLog(long reservationId, long operatorId) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, 'IN_USE', 'COMPLETED', ?, 'Web账号签退')
                """, reservationId, operatorId);
    }

    public boolean hasCheckin(long reservationId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation
                WHERE resv_id = ? AND checkin_time IS NOT NULL
                """, Integer.class, reservationId);
        return count != null && count > 0;
    }

    public int markNoShow(long reservationId, int graceMinutes, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE t_reservation SET status = 'NO_SHOW'
                WHERE resv_id = ? AND status = 'APPROVED' AND checkin_time IS NULL
                  AND start_time < DATE_SUB(?, INTERVAL ? MINUTE)
                """, reservationId, Timestamp.valueOf(now), graceMinutes);
    }

    public int completeEndedReservation(long reservationId, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE t_reservation
                SET status = 'COMPLETED', checkout_time = end_time
                WHERE resv_id = ? AND status = 'IN_USE' AND end_time <= ?
                """, reservationId, Timestamp.valueOf(now));
    }

    public int expirePendingReservation(long reservationId, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE t_reservation SET status = 'EXPIRED'
                WHERE resv_id = ? AND status = 'PENDING_SCHEDULE'
                  AND DATE_ADD(TIMESTAMP(request_date, '00:00:00'),
                               INTERVAL TIME_TO_SEC(request_window_end) SECOND) <= ?
                """, reservationId, Timestamp.valueOf(now));
    }

    public void insertSystemLog(
            long reservationId, ReservationStatus fromStatus, ReservationStatus toStatus, String reason) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, ?, ?, NULL, ?)
                """, reservationId, fromStatus.name(), toStatus.name(), reason);
    }

    public int cancelReservation(long reservationId, long userId, ReservationStatus expectedStatus) {
        return jdbcTemplate.update("""
                UPDATE t_reservation SET status = 'CANCELLED'
                WHERE resv_id = ? AND user_id = ? AND status = ?
                """, reservationId, userId, expectedStatus.name());
    }

    public int cancellationFreeBeforeHours() {
        String value = jdbcTemplate.queryForObject(
                "SELECT config_value FROM t_sys_config WHERE config_key = 'cancel.free.before.hours'",
                String.class);
        if (value == null) {
            throw new IllegalStateException("缺少免费取消时间配置");
        }
        return Integer.parseInt(value);
    }

    public void insertCancellationLog(
            long reservationId, ReservationStatus fromStatus, long operatorId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, ?, 'CANCELLED', ?, ?)
                """, reservationId, fromStatus.name(), operatorId, reason);
    }

    public void insertInitialLog(long reservationId, long operatorId, ReservationStatus status) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, NULL, ?, ?, '创建预约')
                """, reservationId, status.name(), operatorId);
    }

    public record CancellationTarget(Long labId, LocalDate date) {
    }

    public record CancellationReservation(
            long resvId,
            long userId,
            LocalDate date,
            LocalTime requestWindowStart,
            Long labId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            ReservationMode reservationMode,
            Integer seatCount,
            ReservationStatus status) {
    }

    public record AssignedSeatBitmap(
            long seatId, long assignmentMask, long currentMask, long version) {
    }

    public long countViews(Long ownerId, Long labId, LocalDate date, ReservationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM t_reservation WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        appendFilters(sql, arguments, ownerId, labId, date, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, arguments.toArray());
        return total == null ? 0 : total;
    }

    public List<Long> listViewIds(
            Long ownerId, Long labId, LocalDate date, ReservationStatus status, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT resv_id FROM t_reservation WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        appendFilters(sql, arguments, ownerId, labId, date, status);
        sql.append(" ORDER BY created_at DESC, resv_id DESC LIMIT ? OFFSET ?");
        arguments.add(size);
        arguments.add((long) page * size);
        return jdbcTemplate.queryForList(sql.toString(), Long.class, arguments.toArray());
    }

    public List<ReservationView> listViewsByBatch(long batchId) {
        return listViewsByIds(listReservationIdsByBatch(batchId));
    }

    public List<ReservationView> listViewsByIds(List<Long> reservationIds) {
        if (reservationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(reservationIds.size(), "?"));
        Map<Long, List<ReservationSeatView>> seatsByReservation =
                findSeatsByReservationIds(reservationIds, placeholders);
        String sql = """
                SELECT r.resv_id, r.user_id, r.batch_id, b.batch_no, r.lab_id, l.name AS lab_name,
                       r.request_date, r.request_window_start, r.request_window_end,
                       r.requested_duration_minutes, r.start_time, r.end_time,
                       r.allocation_mode, r.reservation_mode, r.status,
                       r.attendee_count, r.seat_count, r.purpose, r.preferences,
                       r.checkin_channel, r.checkin_time, r.checkout_channel, r.checkout_time,
                       r.created_at
                FROM t_reservation r
                LEFT JOIN t_lab l ON l.lab_id = r.lab_id
                LEFT JOIN t_reservation_batch b ON b.batch_id = r.batch_id
                WHERE r.resv_id IN (""" + placeholders + ")";
        Map<Long, ReservationView> viewsById = jdbcTemplate.query(sql, resultSet -> {
            Map<Long, ReservationView> values = new HashMap<>();
            while (resultSet.next()) {
                long reservationId = resultSet.getLong("resv_id");
                values.put(reservationId, mapView(
                        resultSet, seatsByReservation.getOrDefault(reservationId, List.of())));
            }
            return values;
        }, reservationIds.toArray());
        return reservationIds.stream().map(viewsById::get).toList();
    }

    public ReservationView findView(long reservationId) {
        List<ReservationView> views = listViewsByIds(List.of(reservationId));
        return views.isEmpty() ? null : views.getFirst();
    }

    private Map<Long, List<ReservationSeatView>> findSeatsByReservationIds(
            List<Long> reservationIds, String placeholders) {
        String sql = """
                SELECT rs.resv_id AS reservation_id, rs.seat_id, s.seat_code, s.zone_code,
                       s.seat_index, s.spec_level, rs.time_mask,
                       COALESCE((JSON_TYPE(JSON_EXTRACT(r.preferences, '$.zoneCode')) = 'STRING'
                           AND JSON_UNQUOTE(JSON_EXTRACT(r.preferences, '$.zoneCode'))
                           <> s.zone_code), FALSE) AS zone_mismatch,
                       COALESCE((JSON_UNQUOTE(JSON_EXTRACT(r.preferences, '$.nearWindow')) = 'true'
                           AND s.near_window = FALSE), FALSE) AS near_window_mismatch
                FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id
                JOIN t_lab_seat s ON s.seat_id = rs.seat_id
                WHERE rs.resv_id IN (""" + placeholders + ") ORDER BY rs.resv_id, s.seat_index, s.seat_id";
        return jdbcTemplate.query(sql, resultSet -> {
            Map<Long, List<ReservationSeatView>> values = new HashMap<>();
            while (resultSet.next()) {
                values.computeIfAbsent(resultSet.getLong("reservation_id"), key -> new ArrayList<>())
                        .add(mapSeat(resultSet));
            }
            return values;
        }, reservationIds.toArray());
    }

    private ReservationSeatView mapSeat(ResultSet rs) throws SQLException {
        return new ReservationSeatView(
                rs.getLong("seat_id"), rs.getString("seat_code"), rs.getString("zone_code"),
                rs.getInt("seat_index"), rs.getString("spec_level"),
                Long.toUnsignedString(rs.getLong("time_mask")),
                rs.getBoolean("zone_mismatch"), rs.getBoolean("near_window_mismatch"));
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> arguments,
            Long ownerId,
            Long labId,
            LocalDate date,
            ReservationStatus status) {
        if (ownerId != null) {
            sql.append(" AND user_id = ?");
            arguments.add(ownerId);
        }
        if (labId != null) {
            sql.append(" AND lab_id = ?");
            arguments.add(labId);
        }
        if (date != null) {
            sql.append(" AND request_date = ?");
            arguments.add(date);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        }
    }

    private ReservationView mapView(ResultSet rs, List<ReservationSeatView> seats) throws SQLException {
        return new ReservationView(
                rs.getLong("user_id"), rs.getLong("resv_id"),
                rs.getObject("batch_id", Long.class), rs.getString("batch_no"),
                rs.getObject("lab_id", Long.class), rs.getString("lab_name"),
                rs.getObject("request_date", LocalDate.class),
                rs.getString("request_window_start"), rs.getString("request_window_end"),
                rs.getInt("requested_duration_minutes"),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class),
                AllocationMode.valueOf(rs.getString("allocation_mode")),
                ReservationMode.valueOf(rs.getString("reservation_mode")),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getInt("attendee_count"), rs.getObject("seat_count", Integer.class),
                rs.getString("purpose"), preferencesJson(rs.getString("preferences")), seats,
                rs.getString("checkin_channel"), rs.getObject("checkin_time", LocalDateTime.class),
                rs.getString("checkout_channel"), rs.getObject("checkout_time", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private JsonNode preferencesJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("预约偏好JSON损坏", exception);
        }
    }

    private String preferencesJson(NormalizedReservation request) {
        try {
            return objectMapper.writeValueAsString(request.preferences());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("预约偏好无法序列化", exception);
        }
    }

    private static int startSecond(LocalTime time) {
        return time.toSecondOfDay();
    }

    private static int endSecond(LocalTime time) {
        return LocalTime.MIDNIGHT.equals(time) ? 24 * 60 * 60 : time.toSecondOfDay();
    }

    private static String requestTime(LocalTime time, boolean end) {
        if (end && LocalTime.MIDNIGHT.equals(time)) {
            return "24:00:00";
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                time.getHour(), time.getMinute(), time.getSecond());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    public record UserAdmission(String status, int creditScore) {
    }

    public record LabDetails(long labId, String name, int capacity, ResourceStatus status) {
    }

    public record LabBitmap(long mask, long version) {
    }

    public record SeatBitmap(SeatSnapshot snapshot, long version) {
    }
}
