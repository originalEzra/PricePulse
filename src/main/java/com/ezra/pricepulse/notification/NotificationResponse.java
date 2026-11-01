package com.ezra.pricepulse.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record NotificationResponse(
        long id,
        Long triggerId,
        Long ruleId,
        Long stockId,
        String stockSymbol,
        String stockName,
        BigDecimal triggerPrice,
        LocalDateTime eventTime,
        String title,
        String content,
        NotificationStatus status,
        LocalDateTime readAt,
        LocalDateTime createdAt,
        String eventType) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(), notification.getTriggerId(), notification.getRuleId(),
                notification.getStockId(), notification.getStockSymbol(), notification.getStockName(),
                notification.getTriggerPrice(), notification.getEventTime(), notification.getTitle(),
                notification.getContent(), notification.getStatus(), notification.getReadAt(),
                notification.getCreatedAt(),notification.getEventType());
    }
}
