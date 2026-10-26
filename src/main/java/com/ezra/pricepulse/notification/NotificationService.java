package com.ezra.pricepulse.notification;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class NotificationService {
    private final NotificationMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationService(NotificationMapper mapper, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void consume(String payload) {
        AlertTriggeredEvent event = parseEvent(payload);
        event.validate();
        AlertTriggerDelivery delivery = mapper.findDelivery(event.triggerId());
        if (delivery == null
                || delivery.getUserId() != event.userId()
                || delivery.getRuleId() != event.ruleId()
                || delivery.getStockId() != event.stockId()) {
            throw new IllegalArgumentException("Alert event does not match its persisted trigger");
        }
        NotificationText text = createText(delivery);
        mapper.insertIfAbsent(delivery.getUserId(), delivery.getTriggerId(), text.title(), text.content());
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(long userId, String requestedStatus, int limit) {
        NotificationStatus status = parseStatus(requestedStatus);
        return mapper.listOwned(userId, status, limit).stream().map(NotificationResponse::from).toList();
    }

    @Transactional
    public NotificationResponse markRead(long userId, long notificationId) {
        Notification existing = mapper.findOwned(userId, notificationId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Notification was not found");
        }
        if (existing.getStatus() == NotificationStatus.UNREAD) {
            mapper.markRead(userId, notificationId, now());
        }
        return NotificationResponse.from(mapper.findOwned(userId, notificationId));
    }

    private AlertTriggeredEvent parseEvent(String payload) {
        try {
            return json.readValue(payload, AlertTriggeredEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Alert event payload is malformed", exception);
        }
    }

    private NotificationText createText(AlertTriggerDelivery delivery) {
        try {
            JsonNode snapshot = json.readTree(delivery.getRuleSnapshot());
            String ruleName = snapshot.path("name").asText();
            if (ruleName.isBlank()) {
                ruleName = "价格提醒";
            }
            String direction = snapshot.path("direction").asText();
            String threshold = snapshot.path("thresholdPrice").asText();
            if (threshold.isBlank()) {
                throw new IllegalStateException("Rule snapshot does not contain a threshold price");
            }
            String verb = switch (direction) {
                case "ABOVE" -> "上破";
                case "BELOW" -> "下破";
                default -> throw new IllegalStateException("Rule snapshot contains an unknown direction");
            };
            String title = delivery.getStockSymbol() + " 已" + verb + " " + threshold;
            String content = "%s（%s）价格 %s，触发提醒“%s”（%s %s）。".formatted(
                    delivery.getStockName(), delivery.getStockSymbol(),
                    delivery.getTriggerPrice().toPlainString(), ruleName, verb, threshold);
            return new NotificationText(title, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read the persisted rule snapshot", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private NotificationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NotificationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Unknown notification status: " + value);
        }
    }

    private record NotificationText(String title, String content) { }
}
