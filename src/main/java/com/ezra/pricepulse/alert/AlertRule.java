package com.ezra.pricepulse.alert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AlertRule {
    private Long id;
    private Long userId;
    private Long stockId;
    private String stockSymbol;
    private String stockName;
    private String name;
    private String note;
    private AlertDirection direction;
    private BigDecimal thresholdPrice;
    private AlertTriggerMode triggerMode;
    private BigDecimal rearmPrice;
    private AlertRuleStatus status;
    private LocalDateTime lastTriggeredAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime effectiveAt;

    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime value) { effectiveAt=value; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getStockSymbol() { return stockSymbol; }
    public void setStockSymbol(String stockSymbol) { this.stockSymbol = stockSymbol; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public AlertDirection getDirection() { return direction; }
    public void setDirection(AlertDirection direction) { this.direction = direction; }
    public BigDecimal getThresholdPrice() { return thresholdPrice; }
    public void setThresholdPrice(BigDecimal thresholdPrice) { this.thresholdPrice = thresholdPrice; }
    public AlertTriggerMode getTriggerMode() { return triggerMode; }
    public void setTriggerMode(AlertTriggerMode triggerMode) { this.triggerMode = triggerMode; }
    public BigDecimal getRearmPrice() { return rearmPrice; }
    public void setRearmPrice(BigDecimal rearmPrice) { this.rearmPrice = rearmPrice; }
    public AlertRuleStatus getStatus() { return status; }
    public void setStatus(AlertRuleStatus status) { this.status = status; }
    public LocalDateTime getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
