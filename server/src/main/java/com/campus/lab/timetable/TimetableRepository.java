package com.campus.lab.timetable;

import com.campus.lab.checkin.CheckinChannel;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.timetable.dto.MyTimetableSessionView;
import com.campus.lab.timetable.dto.RosterMemberView;
import com.campus.lab.timetable.dto.SemesterView;
import com.campus.lab.timetable.dto.TimetableConflictView;
import com.campus.lab.timetable.dto.TimetableEntryView;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TimetableRepository {

    private final JdbcTemplate jdbcTemplate;

    public TimetableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertSemester(String code, String name, LocalDate week1Monday, int weekCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_semester (code, name, week1_monday, week_count, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setDate(3, java.sql.Date.valueOf(week1Monday));
            statement.setInt(4, weekCount);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("学期写入后未获得主键");
        }
        return key.longValue();
    }

    public void updateSemester(long semesterId, String code, String name, LocalDate week1Monday, int weekCount) {
        jdbcTemplate.update("""
                UPDATE t_semester
                SET code = ?, name = ?, week1_monday = ?, week_count = ?
                WHERE semester_id = ?
                """, code, name, week1Monday, weekCount, semesterId);
    }

    public SemesterRow findSemester(long semesterId) {
        List<SemesterRow> rows = jdbcTemplate.query("""
                SELECT semester_id, code, name, week1_monday, week_count, status
                FROM t_semester WHERE semester_id = ?
                """, (rs, rowNum) -> new SemesterRow(
                rs.getLong("semester_id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("week1_monday", LocalDate.class), rs.getInt("week_count"),
                rs.getString("status")), semesterId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SemesterRow findSemesterByCode(String code) {
        List<SemesterRow> rows = jdbcTemplate.query("""
                SELECT semester_id, code, name, week1_monday, week_count, status
                FROM t_semester WHERE code = ?
                """, (rs, rowNum) -> new SemesterRow(
                rs.getLong("semester_id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("week1_monday", LocalDate.class), rs.getInt("week_count"),
                rs.getString("status")), code);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<SemesterView> listSemesters() {
        return jdbcTemplate.query("""
                SELECT semester_id, code, name, week1_monday, week_count, status
                FROM t_semester ORDER BY week1_monday, semester_id
                """, (rs, rowNum) -> new SemesterView(
                rs.getLong("semester_id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("week1_monday", LocalDate.class), rs.getInt("week_count"),
                rs.getString("status")));
    }

    public boolean semesterCodeTaken(String code, Long exceptId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_semester WHERE code = ? AND (? IS NULL OR semester_id <> ?)
                """, Integer.class, code, exceptId, exceptId);
        return count != null && count > 0;
    }

    public int countEntries(long semesterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timetable_entry WHERE semester_id = ?",
                Integer.class, semesterId);
        return count == null ? 0 : count;
    }

    public boolean administers(long userId, long labId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_lab_admin WHERE user_id = ? AND lab_id = ?
                """, Integer.class, userId, labId);
        return count != null && count > 0;
    }

    public Long labIdByCode(String labCode) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?",
                (rs, rowNum) -> rs.getLong(1), labCode);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<LabView> listWritableLabs(Long adminUserId) {
        if (adminUserId == null) {
            return jdbcTemplate.query("""
                    SELECT lab_id, lab_code, name, location, capacity, status
                    FROM t_lab ORDER BY lab_id
                    """, this::mapLab);
        }
        return jdbcTemplate.query("""
                SELECT l.lab_id, l.lab_code, l.name, l.location, l.capacity, l.status
                FROM t_lab l
                JOIN t_lab_admin a ON a.lab_id = l.lab_id AND a.user_id = ?
                ORDER BY l.lab_id
                """, this::mapLab, adminUserId);
    }

    public Long activeUserId(String account, String roleCode) {
        List<Long> ids = jdbcTemplate.query("""
                SELECT u.user_id
                FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id = u.user_id
                JOIN sys_role r ON r.role_id = ur.role_id AND r.role_code = ?
                WHERE u.account = ? AND u.status = 'ACTIVE'
                """, (rs, rowNum) -> rs.getLong(1), roleCode, account);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public long insertEntry(TimetableDraft draft, long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_timetable_entry
                        (semester_id, lab_id, course_name, teacher_user_id, class_name,
                         day_of_week, start_period, end_period, week_start, week_end,
                         status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, draft.semesterId());
            statement.setLong(2, draft.labId());
            statement.setString(3, draft.courseName());
            statement.setLong(4, draft.teacherUserId());
            statement.setString(5, draft.className());
            statement.setInt(6, draft.dayOfWeek());
            statement.setInt(7, draft.startPeriod());
            statement.setInt(8, draft.endPeriod());
            statement.setInt(9, draft.weekStart());
            statement.setInt(10, draft.weekEnd());
            statement.setLong(11, createdBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("课表写入后未获得主键");
        }
        return key.longValue();
    }

    public void insertRoster(long entryId, long userId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO t_timetable_roster (entry_id, user_id) VALUES (?, ?)",
                entryId, userId);
    }

    public int deleteRoster(long entryId, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM t_timetable_roster WHERE entry_id = ? AND user_id = ?",
                entryId, userId);
    }

    public void insertSession(long entryId, TimetableDraft.Session session, long reservationId) {
        jdbcTemplate.update("""
                INSERT INTO t_timetable_session (entry_id, week_no, session_date, resv_id, status)
                VALUES (?, ?, ?, ?, 'SCHEDULED')
                """, entryId, session.weekNo(), session.date(), reservationId);
    }

    public void markTimetableReservation(long reservationId) {
        int updated = jdbcTemplate.update("""
                UPDATE t_reservation SET occupancy_source = 'TIMETABLE' WHERE resv_id = ?
                """, reservationId);
        if (updated != 1) {
            throw new IllegalStateException("课表预约来源未写上");
        }
    }

    public List<TimetableConflictView> findHolders(
            long labId, LocalDate date, long mask, int rowNumber, int weekNo,
            int dayOfWeek, int startPeriod, int endPeriod) {
        return jdbcTemplate.query("""
                SELECT DISTINCT r.resv_id, r.user_id, u.account, u.name AS user_name, r.purpose,
                       r.reservation_mode, r.occupancy_source, r.start_time, r.end_time
                FROM t_reservation r
                JOIN sys_user u ON u.user_id = r.user_id
                JOIN t_reservation_seat rs ON rs.resv_id = r.resv_id
                WHERE r.lab_id = ? AND r.request_date = ?
                  AND r.status IN ('APPROVED', 'IN_USE')
                  AND (rs.time_mask & ?) <> 0
                ORDER BY r.resv_id
                """, (rs, rowNum) -> mapConflict(rs, rowNumber, date, weekNo, dayOfWeek, startPeriod, endPeriod),
                labId, date, mask);
    }

    public List<TimetableConflictView> findTeacherSessions(
            long teacherUserId, LocalDate date, long mask, int rowNumber, int weekNo,
            int dayOfWeek, int startPeriod, int endPeriod) {
        return jdbcTemplate.query("""
                SELECT DISTINCT r.resv_id, r.user_id, u.account, u.name AS user_name,
                       CONCAT(e.course_name, ' ', e.class_name, ' @ ', l.lab_code) AS purpose,
                       r.reservation_mode, r.occupancy_source, r.start_time, r.end_time
                FROM t_timetable_session s
                JOIN t_timetable_entry e ON e.entry_id = s.entry_id AND e.status = 'ACTIVE'
                JOIN t_lab l ON l.lab_id = e.lab_id
                JOIN t_reservation r ON r.resv_id = s.resv_id AND r.status IN ('APPROVED', 'IN_USE')
                JOIN t_reservation_seat rs ON rs.resv_id = r.resv_id
                JOIN sys_user u ON u.user_id = e.teacher_user_id
                WHERE e.teacher_user_id = ? AND s.session_date = ? AND s.status = 'SCHEDULED'
                  AND (rs.time_mask & ?) <> 0
                ORDER BY r.resv_id
                """, (rs, rowNum) -> mapConflict(rs, rowNumber, date, weekNo, dayOfWeek, startPeriod, endPeriod),
                teacherUserId, date, mask);
    }

    public TimetableEntryView findEntry(long entryId) {
        List<TimetableEntryView> entries = list(null, null, null, entryId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public List<TimetableEntryView> list(Long semesterId, Long labId, Long adminUserId, Long entryId) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.entry_id, e.semester_id, sem.code AS semester_code, e.lab_id,
                       l.lab_code, l.name AS lab_name, e.course_name, e.teacher_user_id,
                       t.account AS teacher_account, t.name AS teacher_name, e.class_name,
                       e.day_of_week, e.start_period, e.end_period, e.week_start, e.week_end,
                       e.status,
                       (SELECT COUNT(*) FROM t_timetable_session s
                        WHERE s.entry_id = e.entry_id AND s.status = 'SCHEDULED') AS session_count
                FROM t_timetable_entry e
                JOIN t_semester sem ON sem.semester_id = e.semester_id
                JOIN t_lab l ON l.lab_id = e.lab_id
                JOIN sys_user t ON t.user_id = e.teacher_user_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (entryId != null) {
            sql.append(" AND e.entry_id = ?");
            args.add(entryId);
        }
        if (semesterId != null) {
            sql.append(" AND e.semester_id = ?");
            args.add(semesterId);
        }
        if (labId != null) {
            sql.append(" AND e.lab_id = ?");
            args.add(labId);
        }
        if (adminUserId != null) {
            sql.append(" AND e.lab_id IN (SELECT lab_id FROM t_lab_admin WHERE user_id = ?)");
            args.add(adminUserId);
        }
        sql.append(" ORDER BY e.entry_id DESC");
        List<TimetableEntryView> entries = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TimetableEntryView(
                rs.getLong("entry_id"), rs.getLong("semester_id"), rs.getString("semester_code"),
                rs.getLong("lab_id"), rs.getString("lab_code"), rs.getString("lab_name"),
                rs.getString("course_name"), rs.getLong("teacher_user_id"),
                rs.getString("teacher_account"), rs.getString("teacher_name"),
                rs.getString("class_name"), rs.getInt("day_of_week"), rs.getInt("start_period"),
                rs.getInt("end_period"), rs.getInt("week_start"), rs.getInt("week_end"),
                rs.getString("status"), rs.getInt("session_count"), List.of()), args.toArray());
        if (entries.isEmpty()) {
            return entries;
        }
        Map<Long, List<RosterMemberView>> rosters = new LinkedHashMap<>();
        String placeholders = String.join(",", entries.stream().map(entry -> "?").toList());
        Object[] rosterArgs = entries.stream().map(TimetableEntryView::entryId).toArray();
        jdbcTemplate.query("""
                SELECT r.entry_id, u.user_id, u.account, u.name
                FROM t_timetable_roster r
                JOIN sys_user u ON u.user_id = r.user_id
                WHERE r.entry_id IN (""" + placeholders + ") ORDER BY u.account",
                rs -> {
                    long id = rs.getLong("entry_id");
                    rosters.computeIfAbsent(id, key -> new ArrayList<>()).add(new RosterMemberView(
                            rs.getLong("user_id"), rs.getString("account"), rs.getString("name")));
                }, rosterArgs);
        return entries.stream().map(entry -> new TimetableEntryView(
                entry.entryId(), entry.semesterId(), entry.semesterCode(), entry.labId(),
                entry.labCode(), entry.labName(), entry.courseName(), entry.teacherUserId(),
                entry.teacherAccount(), entry.teacherName(), entry.className(), entry.dayOfWeek(),
                entry.startPeriod(), entry.endPeriod(), entry.weekStart(), entry.weekEnd(),
                entry.status(), entry.sessionCount(), rosters.getOrDefault(entry.entryId(), List.of())))
                .toList();
    }

    public List<MyTimetableSessionView> mySessions(long userId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT s.session_id, e.entry_id, s.resv_id, e.lab_id, l.name AS lab_name,
                       e.course_name, e.class_name, s.session_date, s.week_no,
                       e.start_period, e.end_period, r.start_time, r.end_time, r.status,
                       EXISTS (
                           SELECT 1 FROM t_timetable_attendance a
                           WHERE a.session_id = s.session_id AND a.user_id = ?
                       ) AS checked_in
                FROM t_timetable_session s
                JOIN t_timetable_entry e ON e.entry_id = s.entry_id AND e.status = 'ACTIVE'
                JOIN t_lab l ON l.lab_id = e.lab_id
                JOIN t_reservation r ON r.resv_id = s.resv_id
                WHERE s.status = 'SCHEDULED' AND s.session_date BETWEEN ? AND ?
                  AND (
                    e.teacher_user_id = ?
                    OR EXISTS (
                        SELECT 1 FROM t_timetable_roster roster
                        WHERE roster.entry_id = e.entry_id AND roster.user_id = ?
                    )
                  )
                ORDER BY s.session_date, e.start_period, s.session_id
                """, (rs, rowNum) -> new MyTimetableSessionView(
                rs.getLong("session_id"), rs.getLong("entry_id"), rs.getLong("resv_id"),
                rs.getLong("lab_id"), rs.getString("lab_name"), rs.getString("course_name"),
                rs.getString("class_name"), rs.getObject("session_date", LocalDate.class),
                rs.getInt("week_no"), rs.getInt("start_period"), rs.getInt("end_period"),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class), rs.getString("status"),
                rs.getBoolean("checked_in")), userId, from, to, userId, userId);
    }

    public boolean onRoster(long reservationId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_timetable_session s
                JOIN t_timetable_entry e ON e.entry_id = s.entry_id AND e.status = 'ACTIVE'
                JOIN t_timetable_roster r ON r.entry_id = s.entry_id AND r.user_id = ?
                WHERE s.resv_id = ? AND s.status = 'SCHEDULED'
                """, Integer.class, userId, reservationId);
        return count != null && count > 0;
    }

    public boolean hasAttendance(long reservationId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_timetable_attendance a
                JOIN t_timetable_session s ON s.session_id = a.session_id
                WHERE s.resv_id = ? AND a.user_id = ?
                """, Integer.class, reservationId, userId);
        return count != null && count > 0;
    }

    public void insertAttendance(
            long reservationId, long userId, LocalDateTime checkinTime, CheckinChannel channel) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO t_timetable_attendance (session_id, user_id, checkin_time, channel)
                SELECT session_id, ?, ?, ? FROM t_timetable_session WHERE resv_id = ?
                """, userId, Timestamp.valueOf(checkinTime), channel.name(), reservationId);
        if (inserted != 1) {
            throw new IllegalStateException("课表签到未写入");
        }
    }

    private LabView mapLab(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LabView(
                rs.getLong("lab_id"), rs.getString("lab_code"), rs.getString("name"),
                rs.getString("location"), rs.getInt("capacity"),
                ResourceStatus.valueOf(rs.getString("status")));
    }

    private static TimetableConflictView mapConflict(
            java.sql.ResultSet rs, int rowNumber, LocalDate date, int weekNo, int dayOfWeek,
            int startPeriod, int endPeriod) throws java.sql.SQLException {
        return new TimetableConflictView(
                rowNumber, date, weekNo, dayOfWeek, startPeriod, endPeriod,
                rs.getLong("resv_id"), rs.getLong("user_id"), rs.getString("account"),
                rs.getString("user_name"), rs.getString("purpose"),
                rs.getString("reservation_mode"), rs.getString("occupancy_source"),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class));
    }

    public record SemesterRow(
            long semesterId, String code, String name, LocalDate week1Monday, int weekCount, String status) {
    }
}
