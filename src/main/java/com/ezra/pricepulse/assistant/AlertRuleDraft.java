package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import java.math.BigDecimal;

public record AlertRuleDraft(
        long stockId,
        String stockSymbol,
        String stockName,
        String name,
        String note,
        AlertDirection direction,
        BigDecimal thresholdPrice,
        AlertTriggerMode triggerMode,
        BigDecimal rearmPrice) {
}
