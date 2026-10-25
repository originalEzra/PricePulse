package com.ezra.pricepulse.stock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Stock(
        long id,
        String symbol,
        String name,
        String market,
        String currency,
        BigDecimal latestPrice,
        LocalDateTime latestQuoteAt,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String ruleIndexToken) {
}
