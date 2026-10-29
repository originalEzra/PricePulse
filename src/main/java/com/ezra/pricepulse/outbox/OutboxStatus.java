package com.ezra.pricepulse.outbox;

public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    QUARANTINED
}
