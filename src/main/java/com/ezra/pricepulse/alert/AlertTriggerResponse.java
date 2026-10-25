package com.ezra.pricepulse.alert;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AlertTriggerResponse(
        long id,
        long ruleId,
        long quoteEventId,
        BigDecimal triggerPrice,
        LocalDateTime eventTime,
        JsonNode ruleSnapshot,
        LocalDateTime createdAt) {
}
