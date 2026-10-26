package com.ezra.pricepulse.notification;

public record AlertTriggeredEvent(
        int schemaVersion,
        long triggerId,
        long userId,
        long ruleId,
        long stockId) {

    void validate() {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported alert event schema version: " + schemaVersion);
        }
        if (triggerId <= 0 || userId <= 0 || ruleId <= 0 || stockId <= 0) {
            throw new IllegalArgumentException("Alert event identifiers must be positive");
        }
    }
}
