package com.campus.lab.resource.dto;

public record CheckinRuleView(
        int beforeMinutes,
        int graceMinutes,
        int choiceExpireSeconds,
        int maxFailedAttempts,
        int lockoutMinutes) {
}
