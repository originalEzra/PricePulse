package com.ezra.pricepulse.alert;

import java.math.BigDecimal;

/** A transaction-local observation of one owned rule; not a reconstructed price crossing. */
public record RuleObservation(
        long ruleId,
        String ruleName,
        AlertDirection direction,
        BigDecimal thresholdPrice,
        BigDecimal rearmPrice,
        AlertTriggerMode triggerMode,
        AlertRuleStatus beforeStatus,
        AlertRuleStatus afterStatus,
        String event,
        Long triggerId,
        long version) { }
