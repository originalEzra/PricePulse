package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.ezra.pricepulse.matching.MatchingResult;

public record QuoteResult(
        long eventId,
        String eventKey,
        long stockId,
        BigDecimal previousPrice,
        BigDecimal price,
        LocalDateTime eventTime,
        QuoteOutcome outcome,
        String reason,
        MatchingResult matching) {
}
