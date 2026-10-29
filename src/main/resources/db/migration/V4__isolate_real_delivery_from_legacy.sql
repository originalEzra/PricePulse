ALTER TABLE quote_events
    ADD COLUMN origin_type VARCHAR(8) NOT NULL DEFAULT 'LEGACY',
    ADD CONSTRAINT chk_quote_origin CHECK (origin_type IN ('LEGACY', 'REAL')),
    ADD INDEX idx_quote_real_stock_time (origin_type, stock_id, event_time, id);

ALTER TABLE outbox_events
    DROP CHECK chk_outbox_events_status,
    ADD CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'QUARANTINED'));

-- Retain V1 facts. They are not real-market notifications.
UPDATE outbox_events
SET status = 'QUARANTINED', locked_at = NULL, locked_by = NULL,
    last_error = 'V1 event retained as LEGACY during V2 isolation'
WHERE aggregate_type = 'ALERT_TRIGGER' AND event_type = 'ALERT_TRIGGERED'
  AND status IN ('PENDING', 'FAILED', 'SENDING');
