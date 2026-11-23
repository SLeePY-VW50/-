package com.campus.lab.period;

import com.campus.lab.common.BusinessException;
import com.campus.lab.common.DayTimes;
import com.campus.lab.period.dto.AssignPeriodSetRequest;
import com.campus.lab.period.dto.PeriodSetView;
import com.campus.lab.period.dto.SavePeriodSetRequest;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeriodAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final PeriodCatalog catalog;

    public PeriodAdminService(JdbcTemplate jdbcTemplate, PeriodCatalog catalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalog = catalog;
    }

    public List<PeriodSetView> list() {
        return catalog.list().stream().map(PeriodSetView::from).toList();
    }

    public PeriodSetView get(long periodSetId) {
        return PeriodSetView.from(catalog.require(periodSetId));
    }

    @Transactional
    public PeriodSetView create(SavePeriodSetRequest request) {
        PeriodSchedule parsed = parse(0, request);
        if (request.makeDefault()) {
            jdbcTemplate.update("UPDATE t_period_set SET is_default = FALSE");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO t_period_set (code, name, is_default) VALUES (?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, parsed.code());
            statement.setString(2, parsed.name());
            statement.setBoolean(3, request.makeDefault());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("节次表写入后未获得主键");
        }
        insertPeriods(key.longValue(), parsed);
        return PeriodSetView.from(catalog.require(key.longValue()));
    }

    @Transactional
    public PeriodSetView replace(long periodSetId, SavePeriodSetRequest request) {
        catalog.require(periodSetId);
        PeriodSchedule parsed = parse(periodSetId, request);
        if (request.makeDefault()) {
            jdbcTemplate.update("UPDATE t_period_set SET is_default = FALSE WHERE period_set_id <> ?", periodSetId);
        }
        jdbcTemplate.update(
                "UPDATE t_period_set SET code = ?, name = ?, is_default = ? WHERE period_set_id = ?",
                parsed.code(), parsed.name(), request.makeDefault(), periodSetId);
        jdbcTemplate.update("DELETE FROM t_period WHERE period_set_id = ?", periodSetId);
        insertPeriods(periodSetId, parsed);
        return PeriodSetView.from(catalog.require(periodSetId));
    }

    @Transactional
    public void assignToLab(long labId, AssignPeriodSetRequest request) {
        catalog.require(request.periodSetId());
        int updated = jdbcTemplate.update(
                "UPDATE t_lab SET period_set_id = ? WHERE lab_id = ?", request.periodSetId(), labId);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
    }

    private void insertPeriods(long periodSetId, PeriodSchedule schedule) {
        for (ClassPeriod period : schedule.periods()) {
            jdbcTemplate.update("""
                    INSERT INTO t_period (period_set_id, period_no, start_time, end_time, session)
                    VALUES (?, ?, ?, ?, ?)
                    """, periodSetId, period.number(),
                    DayTimes.format(period.start(), false), DayTimes.format(period.end(), false),
                    period.session().name());
        }
    }

    private static PeriodSchedule parse(long periodSetId, SavePeriodSetRequest request) {
        List<ClassPeriod> periods = new ArrayList<>();
        for (SavePeriodSetRequest.PeriodRequest item : request.periods()) {
            try {
                periods.add(new ClassPeriod(
                        item.periodNo(),
                        DayTimes.parse(item.start(), false),
                        DayTimes.parse(item.end(), false),
                        item.session()));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
            }
        }
        periods.sort(java.util.Comparator.comparingInt(ClassPeriod::number));
        try {
            return new PeriodSchedule(periodSetId, request.code().trim(), request.name().trim(), periods);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
