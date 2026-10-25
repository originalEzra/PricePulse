package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record QuoteCandidate(
        String eventKey,
        long stockId,
        BigDecimal price,
        LocalDateTime eventTime,
        String source,
        Long sequenceNo) {

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
