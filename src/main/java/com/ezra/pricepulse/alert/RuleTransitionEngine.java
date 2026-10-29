package com.ezra.pricepulse.alert;

import java.math.BigDecimal;

/** Pure decision logic; persistence and message delivery belong to the caller. */
public final class RuleTransitionEngine {
    private RuleTransitionEngine() { }

    public record Definition(AlertDirection direction, BigDecimal thresholdPrice,
            AlertTriggerMode triggerMode, BigDecimal rearmPrice) {
        public static Definition from(AlertRule rule) {
            return new Definition(rule.getDirection(), rule.getThresholdPrice(),
                    rule.getTriggerMode(), rule.getRearmPrice());
        }
    }

    public enum Kind { NONE, TRIGGERED, REARMED }

    public record Decision(Kind kind, AlertRuleStatus target) { }

    public static Decision evaluate(Definition rule, AlertRuleStatus state,
            BigDecimal previous, BigDecimal current) {
        if (previous == null) return new Decision(Kind.NONE, state);
        if (state == AlertRuleStatus.ACTIVE) {
            boolean crossed = rule.direction() == AlertDirection.ABOVE
                    ? previous.compareTo(rule.thresholdPrice()) < 0 && current.compareTo(rule.thresholdPrice()) >= 0
                    : previous.compareTo(rule.thresholdPrice()) > 0 && current.compareTo(rule.thresholdPrice()) <= 0;
            if (crossed) return new Decision(Kind.TRIGGERED,
                    rule.triggerMode() == AlertTriggerMode.ONCE ? AlertRuleStatus.COMPLETED : AlertRuleStatus.COOLDOWN);
        } else if (state == AlertRuleStatus.COOLDOWN) {
            boolean rearmed = rule.direction() == AlertDirection.ABOVE
                    ? current.compareTo(rule.rearmPrice()) <= 0 : current.compareTo(rule.rearmPrice()) >= 0;
            if (rearmed) return new Decision(Kind.REARMED, AlertRuleStatus.ACTIVE);
        }
        return new Decision(Kind.NONE, state);
    }
}
