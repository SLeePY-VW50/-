package com.campus.lab.credit;

import com.campus.lab.common.BusinessException;
import com.campus.lab.credit.dto.CreditRecordView;
import com.campus.lab.credit.dto.ViolationView;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class CreditRepository {

    private final JdbcTemplate jdbcTemplate;

    public CreditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int lockUserCredit(long userId) {
        var scores = jdbcTemplate.queryForList(
                "SELECT credit_score FROM sys_user WHERE user_id = ? FOR UPDATE", Integer.class, userId);
        if (scores.isEmpty()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或不可用");
        }
        return scores.getFirst();
    }

    public Integer findCreditScore(long userId) {
        var scores = jdbcTemplate.queryForList(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
        return scores.isEmpty() ? null : scores.getFirst();
    }

    public long countCreditRecords(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_credit_record WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public List<CreditRecordView> listCreditRecords(long userId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT record_id, violation_id, change_type, change_amount,
                       balance_before, balance_after, operator_id, created_at
                FROM t_credit_record WHERE user_id = ?
                ORDER BY created_at DESC, record_id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new CreditRecordView(
                        rs.getLong("record_id"), rs.getObject("violation_id", Long.class),
                        rs.getString("change_type"), rs.getInt("change_amount"),
                        rs.getInt("balance_before"), rs.getInt("balance_after"),
                        rs.getObject("operator_id", Long.class),
                        rs.getObject("created_at", LocalDateTime.class)),
                userId, size, (long) page * size);
    }

    public long countViolations(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_violation WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public List<ViolationView> listViolations(long userId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT v.violation_id, v.resv_id, v.violation_type, v.deduct_points,
                       v.status, v.created_at, v.revoked_at,
                       a.appeal_id, a.status AS appeal_status
                FROM t_violation v
                LEFT JOIN t_appeal a ON a.violation_id = v.violation_id
                WHERE v.user_id = ?
                ORDER BY v.created_at DESC, v.violation_id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new ViolationView(
                        rs.getLong("violation_id"), rs.getLong("resv_id"),
                        rs.getString("violation_type"), rs.getInt("deduct_points"),
                        rs.getString("status"), rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("revoked_at", LocalDateTime.class),
                        rs.getObject("appeal_id", Long.class), rs.getString("appeal_status")),
                userId, size, (long) page * size);
    }

    public ViolationDebit lockViolationDebit(long violationId) {
        List<ViolationDebit> violations = jdbcTemplate.query("""
                SELECT user_id, deduct_points FROM t_violation WHERE violation_id = ? FOR UPDATE
                """, (rs, rowNum) -> new ViolationDebit(rs.getLong(1), rs.getInt(2)), violationId);
        return violations.isEmpty() ? null : violations.getFirst();
    }

    public CreditRecordView findCreditRecordByType(long violationId, String changeType) {
        List<CreditRecordView> records = jdbcTemplate.query("""
                SELECT record_id, violation_id, change_type, change_amount,
                       balance_before, balance_after, operator_id, created_at
                FROM t_credit_record WHERE violation_id = ? AND change_type = ?
                """, (rs, rowNum) -> new CreditRecordView(
                        rs.getLong("record_id"), rs.getObject("violation_id", Long.class),
                        rs.getString("change_type"), rs.getInt("change_amount"),
                        rs.getInt("balance_before"), rs.getInt("balance_after"),
                        rs.getObject("operator_id", Long.class),
                        rs.getObject("created_at", LocalDateTime.class)), violationId, changeType);
        return records.isEmpty() ? null : records.getFirst();
    }

    public long insertViolation(
            long userId, long reservationId, String violationType, int deductPoints, Long operatorId) {
        String sql = """
                INSERT INTO t_violation (user_id, resv_id, violation_type, deduct_points, recorded_by)
                VALUES (?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, reservationId);
            statement.setString(3, violationType);
            statement.setInt(4, deductPoints);
            if (operatorId == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, operatorId);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("违规写入后未获得主键");
        }
        return key.longValue();
    }

    public void updateCreditScore(long userId, int expectedBalance, int newBalance) {
        int updated = jdbcTemplate.update("""
                UPDATE sys_user SET credit_score = ? WHERE user_id = ? AND credit_score = ?
                """, newBalance, userId, expectedBalance);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "信用分已变化，请重试");
        }
    }

    public void insertCreditRecord(
            long userId,
            Long violationId,
            String changeType,
            int changeAmount,
            int balanceBefore,
            int balanceAfter,
            Long operatorId) {
        jdbcTemplate.update("""
                INSERT INTO t_credit_record
                    (user_id, violation_id, change_type, change_amount,
                     balance_before, balance_after, operator_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, violationId, changeType, changeAmount, balanceBefore, balanceAfter, operatorId);
    }

    public record ViolationDebit(long userId, int deductPoints) {
    }
}
