package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record QuoteHistoryResponse(
        long id,
        String eventKey,
        long stockId,
        BigDecimal price,
        LocalDateTime eventTime,
        LocalDateTime receivedAt,
        String source,
        Long sequenceNo,
        QuoteProcessingStatus processingStatus,
        String discardReason) {

    static QuoteHistoryResponse from(QuoteEvent event) {
        return new QuoteHistoryResponse(
                event.getId(),
                event.getEventKey(),
                event.getStockId(),
                event.getPrice(),
                event.getEventTime(),
                event.getReceivedAt(),
                event.getSource(),
                event.getSequenceNo(),
                event.getProcessingStatus(),
                event.getDiscardReason());
    }
}
