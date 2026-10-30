package com.ezra.pricepulse.alert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AlertTrigger {
    private Long id;
    private String triggerKey;
    private Long ruleId;
    private Long quoteEventId;
    private BigDecimal triggerPrice;
    private LocalDateTime eventTime;
    private String ruleSnapshot;
    private String triggerContext;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTriggerKey() { return triggerKey; }
    public void setTriggerKey(String triggerKey) { this.triggerKey = triggerKey; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Long getQuoteEventId() { return quoteEventId; }
    public void setQuoteEventId(Long quoteEventId) { this.quoteEventId = quoteEventId; }
    public BigDecimal getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(BigDecimal triggerPrice) { this.triggerPrice = triggerPrice; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public String getTriggerContext() { return triggerContext; }
    public void setTriggerContext(String value) { triggerContext=value; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
