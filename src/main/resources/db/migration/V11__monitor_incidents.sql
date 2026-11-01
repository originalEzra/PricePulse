-- Additive incident delivery; existing price-trigger notifications retain their FK and unique key.
CREATE TABLE market_monitor_incidents (
    incident_key CHAR(36) NOT NULL PRIMARY KEY,
    active_scope VARCHAR(80) NULL,
    scope VARCHAR(16) NOT NULL,
    stock_id BIGINT UNSIGNED NULL,
    reason VARCHAR(64) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    recovered_at DATETIME(6) NULL,
    discarded_total BIGINT UNSIGNED NOT NULL DEFAULT 0,
    alerted_at DATETIME(6) NULL,
    UNIQUE KEY uk_monitor_active_scope (active_scope),
    CONSTRAINT fk_monitor_incident_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
    CONSTRAINT chk_monitor_scope CHECK ((scope='CONNECTION' AND stock_id IS NULL) OR (scope='QUEUE' AND stock_id IS NOT NULL)),
    CONSTRAINT chk_monitor_recovery CHECK (recovered_at IS NULL OR recovered_at >= started_at)
);

CREATE TABLE market_monitor_deliveries (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_key CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(16) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    symbols VARCHAR(512) NOT NULL,
    UNIQUE KEY uk_monitor_delivery (incident_key,user_id,kind),
    CONSTRAINT fk_monitor_delivery_incident FOREIGN KEY (incident_key) REFERENCES market_monitor_incidents(incident_key),
    CONSTRAINT fk_monitor_delivery_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_monitor_delivery_kind CHECK (kind IN ('INTERRUPTED','RECOVERED'))
);

ALTER TABLE notifications
    MODIFY COLUMN trigger_id BIGINT UNSIGNED NULL,
    ADD COLUMN monitor_delivery_id BIGINT UNSIGNED NULL,
    ADD UNIQUE KEY uk_notification_monitor_delivery (monitor_delivery_id),
    ADD CONSTRAINT fk_notification_monitor_delivery FOREIGN KEY (monitor_delivery_id) REFERENCES market_monitor_deliveries(id),
    ADD CONSTRAINT chk_notification_source CHECK ((trigger_id IS NOT NULL AND monitor_delivery_id IS NULL)
        OR (trigger_id IS NULL AND monitor_delivery_id IS NOT NULL));
