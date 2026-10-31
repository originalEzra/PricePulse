package com.ezra.pricepulse.alert;

import java.time.Instant;
import java.util.Set;

/** A confirmation backed by an actual accepted REAL quote, never an inferred missing trade. */
public record TargetReachContext(String kind,String confirmation,Instant confirmedAt,Instant quoteEventTime,String baselineReason) {
    public TargetReachContext {
        if(!"TARGET_REACHED".equals(kind)||!Set.of("LIVE","RECOVERY","RULE_CREATED","RULE_ACTIVATED").contains(confirmation)
                ||confirmedAt==null||quoteEventTime==null)throw new IllegalArgumentException("Invalid target confirmation");
    }
}
