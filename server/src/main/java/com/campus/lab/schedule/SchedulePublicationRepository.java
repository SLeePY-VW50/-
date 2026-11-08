package com.campus.lab.schedule;

import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.SeatAssignment;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchedulePublicationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ScheduleRepository scheduleRepository;

    public SchedulePublicationRepository(
            JdbcTemplate jdbcTemplate, ScheduleRepository scheduleRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.scheduleRepository = scheduleRepository;
    }

    public List<LabLockKey> listPublicationKeys(long taskId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT r.assigned_lab_id, v.request_date
                FROM t_schedule_result r
                JOIN t_reservation v ON v.resv_id = r.resv_id
                WHERE r.task_id = ? AND r.assigned_lab_id IS NOT NULL
                ORDER BY r.assigned_lab_id, v.request_date
                """, (rs, rowNum) -> new LabLockKey(
                        rs.getLong(1), rs.getObject(2, LocalDate.class)), taskId);
    }

    public List<PublicationResult> lockResults(long taskId) {
        return jdbcTemplate.query("""
                SELECT result_id, task_id, resv_id, assigned_lab_id,
                       assigned_start_time, assigned_end_time, candidate_seat_allocations,
                       is_published, published_at
                FROM t_schedule_result WHERE task_id = ? ORDER BY resv_id FOR UPDATE
                """, (rs, rowNum) -> new PublicationResult(
                        rs.getLong("result_id"), rs.getLong("task_id"), rs.getLong("resv_id"),
                        rs.getObject("assigned_lab_id", Long.class),
                        rs.getObject("assigned_start_time", LocalDateTime.class),
                        rs.getObject("assigned_end_time", LocalDateTime.class),
                        rs.getString("candidate_seat_allocations"), rs.getBoolean("is_published"),
                        rs.getObject("published_at", LocalDateTime.class)), taskId);
    }

    public List<PublicationReservation> lockReservations(List<Long> reservationIds) {
        if (reservationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(reservationIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT resv_id, user_id, created_at, request_date, request_window_start,
                       request_window_end, requested_duration_minutes, allocation_mode,
                       reservation_mode, attendee_count, seat_count, preferences, status
                FROM t_reservation WHERE resv_id IN (""" + placeholders + ") ORDER BY resv_id FOR UPDATE",
                (rs, rowNum) -> mapReservation(rs), reservationIds.toArray());
    }

    private PublicationReservation mapReservation(ResultSet rs) throws SQLException {
        ReservationPreferences preferences = scheduleRepository.readJson(
                rs.getString("preferences"), ReservationPreferences.class);
        if (preferences == null) {
            preferences = ReservationPreferences.empty();
        }
        return new PublicationReservation(
                rs.getLong("resv_id"), rs.getLong("user_id"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("request_date", LocalDate.class),
                DayTimes.parseDatabaseTime(rs.getString("request_window_start")),
                DayTimes.parseDatabaseTime(rs.getString("request_window_end")),
                rs.getInt("requested_duration_minutes"),
                AllocationMode.valueOf(rs.getString("allocation_mode")),
                ReservationMode.valueOf(rs.getString("reservation_mode")),
                rs.getInt("attendee_count"), rs.getObject("seat_count", Integer.class),
                preferences, ReservationStatus.valueOf(rs.getString("status")));
    }

    public List<EffectiveReservation> listEffectiveReservations(
            List<Long> userIds, List<LocalDate> dates, List<Long> batchReservationIds) {
        String userPlaceholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        String datePlaceholders = String.join(",", Collections.nCopies(dates.size(), "?"));
        String batchPlaceholders = String.join(",", Collections.nCopies(batchReservationIds.size(), "?"));
        String sql = """
                SELECT user_id, request_date,
                       COALESCE(start_time, TIMESTAMP(request_date, request_window_start)) AS effective_start,
                       COALESCE(end_time, DATE_ADD(TIMESTAMP(request_date, '00:00:00'),
                           INTERVAL TIME_TO_SEC(request_window_end) SECOND)) AS effective_end
                FROM t_reservation
                WHERE user_id IN (""" + userPlaceholders + ") AND request_date IN (" + datePlaceholders + ")"
                + " AND status IN ('PENDING_SCHEDULE', 'APPROVED', 'IN_USE')"
                + " AND resv_id NOT IN (" + batchPlaceholders + ")"
                + " ORDER BY user_id, resv_id";
        List<Object> arguments = new ArrayList<>(userIds);
        arguments.addAll(dates);
        arguments.addAll(batchReservationIds);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EffectiveReservation(
                rs.getLong("user_id"), rs.getObject("request_date", LocalDate.class),
                rs.getObject("effective_start", LocalDateTime.class),
                rs.getObject("effective_end", LocalDateTime.class)), arguments.toArray());
    }

    public List<SeatAssignment> seatAssignments(PublicationResult result) {
        SeatAssignment[] assignments = scheduleRepository.readJson(
                result.candidateSeatAllocations(), SeatAssignment[].class);
        if (assignments == null) {
            throw new SchedulePublicationException("CANDIDATE_SEATS_MISSING");
        }
        return List.of(assignments);
    }

    public int markResultPublished(long resultId, LocalDateTime publishedAt) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_result SET is_published = TRUE, published_at = ?
                WHERE result_id = ? AND is_published = FALSE
                """, Timestamp.valueOf(publishedAt), resultId);
    }

    public int approveReservation(
            long reservationId, long labId, LocalDateTime start, LocalDateTime end, int seatCount) {
        return jdbcTemplate.update("""
                UPDATE t_reservation
                SET status = 'APPROVED', lab_id = ?, start_time = ?, end_time = ?, seat_count = ?
                WHERE resv_id = ? AND allocation_mode = 'SCHEDULABLE'
                  AND status = 'PENDING_SCHEDULE'
                """, labId, Timestamp.valueOf(start), Timestamp.valueOf(end), seatCount, reservationId);
    }

    public void insertPublishedSeat(long reservationId, long seatId, long mask) {
        jdbcTemplate.update("""
                INSERT INTO t_reservation_seat (resv_id, seat_id, time_mask, assignment_method)
                VALUES (?, ?, ?, 'AUTO_PUBLISH')
                """, reservationId, seatId, mask);
    }

    public int markTaskPublished(long taskId) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task SET status = 'PUBLISHED', error_message = NULL
                WHERE task_id = ? AND is_primary = TRUE AND status = 'SUCCEEDED'
                """, taskId);
    }

    public int markPublishFailed(long taskId, String reason, LocalDateTime finishedAt) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'PUBLISH_FAILED', error_message = ?,
                    finished_at = COALESCE(finished_at, ?)
                WHERE task_id = ? AND is_primary = TRUE AND status = 'SUCCEEDED'
                """, reason, Timestamp.valueOf(finishedAt), taskId);
    }

    public record EffectiveReservation(
            long userId, LocalDate date, LocalDateTime start, LocalDateTime end) {
    }

    public record PublicationResult(
            long resultId, long taskId, long resvId, Long labId,
            LocalDateTime start, LocalDateTime end, String candidateSeatAllocations,
            boolean published, LocalDateTime publishedAt) {
    }

    public record PublicationReservation(
            long resvId, long userId, LocalDateTime createdAt, LocalDate date,
            LocalTime windowStart, LocalTime windowEnd, int durationMinutes,
            AllocationMode allocationMode, ReservationMode mode, int attendeeCount,
            Integer seatCount, ReservationPreferences preferences, ReservationStatus status) {
    }
}
