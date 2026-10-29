package com.ezra.pricepulse.alert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AlertRuleResponse(
        long id,
        long stockId,
        String stockSymbol,
        String stockName,
        String name,
        String note,
        AlertDirection direction,
        BigDecimal thresholdPrice,
        AlertTriggerMode triggerMode,
        BigDecimal rearmPrice,
        AlertRuleStatus status,
        LocalDateTime lastTriggeredAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AlertRuleResponse from(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId(), rule.getStockId(), rule.getStockSymbol(), rule.getStockName(),
                rule.getName(), rule.getNote(), rule.getDirection(), rule.getThresholdPrice(),
                rule.getTriggerMode(), rule.getRearmPrice(), rule.getStatus(), rule.getLastTriggeredAt(),
                rule.getVersion(), rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
