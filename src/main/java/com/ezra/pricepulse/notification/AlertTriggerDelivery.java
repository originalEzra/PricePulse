package com.ezra.pricepulse.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AlertTriggerDelivery {
    private Long triggerId;
    private Long userId;
    private Long ruleId;
    private Long stockId;
    private String stockSymbol;
    private String stockName;
    private BigDecimal triggerPrice;
    private LocalDateTime eventTime;
    private String ruleSnapshot;
    private String triggerContext;
    public String getTriggerContext() { return triggerContext; }
    public void setTriggerContext(String value) { triggerContext=value; }
    private String originType;
    public String getOriginType() { return originType; }
    public void setOriginType(String originType) { this.originType = originType; }

    public Long getTriggerId() { return triggerId; }
    public void setTriggerId(Long triggerId) { this.triggerId = triggerId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getStockSymbol() { return stockSymbol; }
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public BigDecimal getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(BigDecimal triggerPrice) { this.triggerPrice = triggerPrice; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
}
