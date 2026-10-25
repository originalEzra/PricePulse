package com.ezra.pricepulse.stock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockResponse(
        long id,
        String symbol,
        String name,
        String market,
        String currency,
        BigDecimal latestPrice,
        LocalDateTime latestQuoteAt) {

    static StockResponse from(Stock stock) {
        return new StockResponse(
                stock.id(), stock.symbol(), stock.name(), stock.market(), stock.currency(),
                stock.latestPrice(), stock.latestQuoteAt());
    }
}
