package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record QuoteCandidate(
        String eventKey,
        long stockId,
        BigDecimal price,
        LocalDateTime eventTime,
        String source,
        Long sequenceNo,
        java.time.Instant receivedAt,
        String provider,
        String feed,
        String continuityId,
        String gapReason) {

    QuoteCandidate(String eventKey, long stockId, BigDecimal price, LocalDateTime eventTime, String source, Long sequenceNo) {
        this(eventKey, stockId, price, eventTime, source, sequenceNo, null, "INTERNAL", "fixture", "internal", null);
    }

    static QuoteCandidate live(QuoteInputRequest request) {
        return new QuoteCandidate(
                request.eventKey().trim(),
                request.stockId(),
                request.price(),
                request.eventTime(),
                request.source().trim(),
                request.sequenceNo());
    }
}
