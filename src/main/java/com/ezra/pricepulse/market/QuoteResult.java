package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.ezra.pricepulse.matching.MatchingResult;
import com.ezra.pricepulse.alert.RuleObservation;

public record QuoteResult(
        long eventId,
        String eventKey,
        long stockId,
        BigDecimal previousPrice,
        BigDecimal price,
        LocalDateTime eventTime,
        QuoteOutcome outcome,
        String reason,
        MatchingResult matching,
        RuleObservation observation) {
    public QuoteResult(long eventId, String eventKey, long stockId, BigDecimal previousPrice,
            BigDecimal price, LocalDateTime eventTime, QuoteOutcome outcome, String reason,
            MatchingResult matching) {
        this(eventId, eventKey, stockId, previousPrice, price, eventTime, outcome, reason, matching, null);
    }
}
