package com.ezra.pricepulse.matching;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertRule;
import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.ezra.pricepulse.alert.RuleTransitionEngine.Definition;
import com.ezra.pricepulse.common.PriceTicks;

public record RuleIndexEntry(long id, Definition definition, AlertRuleStatus state) {
    public static RuleIndexEntry from(AlertRule rule) {
        return new RuleIndexEntry(rule.getId(), Definition.from(rule), rule.getStatus());
    }

    public Long score() {
        if (state == AlertRuleStatus.ACTIVE) {
            long band = definition.direction() == AlertDirection.ABOVE ? 0 : 1;
            return band * PriceTicks.BAND + PriceTicks.ticks(definition.thresholdPrice());
        }
        if (state == AlertRuleStatus.COOLDOWN) {
            long band = definition.direction() == AlertDirection.ABOVE ? 2 : 3;
            return band * PriceTicks.BAND + PriceTicks.ticks(definition.rearmPrice());
        }
        return null;
    }
}
