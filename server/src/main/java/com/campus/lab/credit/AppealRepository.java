package com.campus.lab.credit;

import com.campus.lab.credit.dto.AppealView;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class AppealRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppealRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ViolationDetails lockViolation(long violationId) {
        List<ViolationDetails> violations = jdbcTemplate.query("""
                SELECT user_id, deduct_points, status FROM t_violation
                WHERE violation_id = ? FOR UPDATE
                """, (rs, rowNum) -> new ViolationDetails(
                        rs.getLong("user_id"), rs.getInt("deduct_points"), rs.getString("status")),
                violationId);
        return violations.isEmpty() ? null : violations.getFirst();
    }

    public int countByViolation(long violationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_appeal WHERE violation_id = ?", Integer.class, violationId);
        return count == null ? 0 : count;
    }

    public long insertAppeal(long violationId, long userId, String reason) {
        String sql = "INSERT INTO t_appeal (violation_id, user_id, reason) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, violationId);
            statement.setLong(2, userId);
            statement.setString(3, reason);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("申诉写入后未获得主键");
        }
        return key.longValue();
    }

    public AppealView lockAppeal(long appealId) {
        return findOne("""
                SELECT appeal_id, violation_id, user_id, reason, status,
                       reviewer_id, review_opinion, reviewed_at, created_at
                FROM t_appeal WHERE appeal_id = ? FOR UPDATE
                """, appealId);
    }

    public AppealView findAppeal(long appealId) {
        return findOne("""
                SELECT appeal_id, violation_id, user_id, reason, status,
                       reviewer_id, review_opinion, reviewed_at, created_at
                FROM t_appeal WHERE appeal_id = ?
                """, appealId);
    }

    private AppealView findOne(String sql, long appealId) {
        List<AppealView> appeals = jdbcTemplate.query(
                sql, (rs, rowNum) -> mapAppeal(rs), appealId);
        return appeals.isEmpty() ? null : appeals.getFirst();
    }

    public int updateReview(
            long appealId, long reviewerId, ReviewDecision decision, String opinion) {
        return jdbcTemplate.update("""
                UPDATE t_appeal
                SET status = ?, reviewer_id = ?, review_opinion = ?, reviewed_at = CURRENT_TIMESTAMP(3)
                WHERE appeal_id = ? AND status = 'PENDING'
                """, decision.name(), reviewerId, opinion, appealId);
    }

    public int revokeViolation(long violationId) {
        return jdbcTemplate.update("""
                UPDATE t_violation SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(3)
                WHERE violation_id = ? AND status = 'ACTIVE'
                """, violationId);
    }

    public long countAppeals(AppealStatus status) {
        if (status == null) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_appeal", Long.class);
            return count == null ? 0 : count;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_appeal WHERE status = ?", Long.class, status.name());
        return count == null ? 0 : count;
    }

    public List<AppealView> listAppeals(AppealStatus status, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT appeal_id, violation_id, user_id, reason, status,
                       reviewer_id, review_opinion, reviewed_at, created_at
                FROM t_appeal
                """);
        List<Object> arguments = new ArrayList<>();
        if (status != null) {
            sql.append(" WHERE status = ?");
            arguments.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, appeal_id DESC LIMIT ? OFFSET ?");
        arguments.add(size);
        arguments.add((long) page * size);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapAppeal(rs), arguments.toArray());
    }

    private AppealView mapAppeal(ResultSet rs) throws SQLException {
        return new AppealView(
                rs.getLong("appeal_id"), rs.getLong("violation_id"), rs.getLong("user_id"),
                rs.getString("reason"), rs.getString("status"),
                rs.getObject("reviewer_id", Long.class), rs.getString("review_opinion"),
                rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class));
    }

    public record ViolationDetails(long userId, int deductPoints, String status) {
    }
}
