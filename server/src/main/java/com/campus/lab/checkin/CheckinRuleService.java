package com.campus.lab.checkin;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CheckinRuleService {

    private final JdbcTemplate jdbcTemplate;

    public CheckinRuleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CheckinRules rules() {
        Map<String, String> values = jdbcTemplate.query("""
                SELECT config_key, config_value FROM t_sys_config
                WHERE config_key IN (
                    'checkin.before.minutes',
                    'checkin.grace.minutes',
                    'checkin.choice.expire.seconds',
                    'checkin.max.failed.attempts',
                    'checkin.lockout.minutes')
                """, resultSet -> {
            Map<String, String> loaded = new HashMap<>();
            while (resultSet.next()) {
                loaded.put(resultSet.getString(1), resultSet.getString(2));
            }
            return loaded;
        });
        CheckinRules rules = new CheckinRules(
                requiredInt(values, "checkin.before.minutes"),
                requiredInt(values, "checkin.grace.minutes"),
                requiredInt(values, "checkin.choice.expire.seconds"),
                requiredInt(values, "checkin.max.failed.attempts"),
                requiredInt(values, "checkin.lockout.minutes"));
        if (rules.beforeMinutes() < 0 || rules.graceMinutes() < 0
                || rules.choiceExpireSeconds() <= 0 || rules.maxFailedAttempts() <= 0
                || rules.lockoutMinutes() <= 0) {
            throw new IllegalStateException("签到规则配置必须为正数或零");
        }
        return rules;
    }

    private static int requiredInt(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("缺少签到规则配置: " + key);
        }
        return Integer.parseInt(value);
    }

    public record CheckinRules(
            int beforeMinutes,
            int graceMinutes,
            int choiceExpireSeconds,
            int maxFailedAttempts,
            int lockoutMinutes) {
    }
}
