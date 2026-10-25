package com.ezra.pricepulse.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class QuoteEvent {
    private Long id;
    private String eventKey;
    private Long stockId;
    private BigDecimal price;
    private LocalDateTime eventTime;
    private LocalDateTime receivedAt;
    private String source;
    private Long sequenceNo;
    private QuoteProcessingStatus processingStatus;
    private String discardReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long sequenceNo) { this.sequenceNo = sequenceNo; }
    public QuoteProcessingStatus getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(QuoteProcessingStatus processingStatus) { this.processingStatus = processingStatus; }
    public String getDiscardReason() { return discardReason; }
    public void setDiscardReason(String discardReason) { this.discardReason = discardReason; }
}
