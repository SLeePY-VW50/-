package com.campus.lab.period;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PeriodCatalog {

    private final JdbcTemplate jdbcTemplate;

    public PeriodCatalog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PeriodSchedule forLab(long labId) {
        List<Long> setIds = jdbcTemplate.query("""
                SELECT period_set_id FROM t_lab WHERE lab_id = ?
                """, (rs, rowNum) -> rs.getObject("period_set_id", Long.class), labId);
        if (setIds.isEmpty()) {
            throw new IllegalArgumentException("实验室不存在");
        }
        Long setId = setIds.getFirst();
        return setId == null ? defaultSchedule() : require(setId);
    }

    public PeriodSchedule defaultSchedule() {
        List<Long> ids = jdbcTemplate.query("""
                SELECT period_set_id FROM t_period_set WHERE is_default = TRUE ORDER BY period_set_id
                """, (rs, rowNum) -> rs.getLong(1));
        if (ids.isEmpty()) {
            throw new IllegalStateException("缺少默认节次表");
        }
        return require(ids.getFirst());
    }

    public PeriodSchedule require(long periodSetId) {
        List<Header> sets = jdbcTemplate.query("""
                SELECT period_set_id, code, name FROM t_period_set WHERE period_set_id = ?
                """, (rs, rowNum) -> new Header(
                rs.getLong("period_set_id"), rs.getString("code"), rs.getString("name")), periodSetId);
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("节次表不存在");
        }
        return schedule(sets.getFirst());
    }

    public List<PeriodSchedule> list() {
        return jdbcTemplate.query("""
                SELECT period_set_id, code, name FROM t_period_set ORDER BY period_set_id
                """, (rs, rowNum) -> new Header(
                rs.getLong("period_set_id"), rs.getString("code"), rs.getString("name")))
                .stream().map(this::schedule).toList();
    }

    private PeriodSchedule schedule(Header header) {
        return new PeriodSchedule(header.id(), header.code(), header.name(), loadPeriods(header.id()));
    }

    private record Header(long id, String code, String name) {
    }

    private List<ClassPeriod> loadPeriods(long periodSetId) {
        return jdbcTemplate.query("""
                SELECT period_no, start_time, end_time, session
                FROM t_period WHERE period_set_id = ? ORDER BY period_no
                """, (rs, rowNum) -> new ClassPeriod(
                rs.getInt("period_no"),
                com.campus.lab.common.DayTimes.parseDatabaseTime(rs.getString("start_time")),
                com.campus.lab.common.DayTimes.parseDatabaseTime(rs.getString("end_time")),
                PeriodSession.valueOf(rs.getString("session"))), periodSetId);
    }
}
