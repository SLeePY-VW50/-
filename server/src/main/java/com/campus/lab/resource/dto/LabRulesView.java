package com.campus.lab.resource.dto;

import com.campus.lab.resource.EffectiveTimeRule;
import java.util.List;

public record LabRulesView(
        long labId,
        List<EffectiveTimeRule> weeklyRules,
        CancellationRuleView cancellation,
        CheckinRuleView checkin) {
}
