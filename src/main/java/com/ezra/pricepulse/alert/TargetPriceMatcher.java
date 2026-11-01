package com.ezra.pricepulse.alert;

import java.math.BigDecimal;

/** Shared REAL/SIM target predicate. No previous-price, cooldown or reactivation state. */
public final class TargetPriceMatcher {
    private TargetPriceMatcher(){}
    public record Definition(AlertDirection direction,BigDecimal thresholdPrice){
        public static Definition from(AlertRule rule){return new Definition(rule.getDirection(),rule.getThresholdPrice());}
    }
    public static boolean reached(Definition rule,BigDecimal current){
        return rule.direction()==AlertDirection.ABOVE?current.compareTo(rule.thresholdPrice())>=0:current.compareTo(rule.thresholdPrice())<=0;
    }
    public static boolean matches(Definition rule,AlertRuleStatus state,BigDecimal current){return state==AlertRuleStatus.ACTIVE&&reached(rule,current);}
}
