CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE stocks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    market VARCHAR(16) NOT NULL,
    currency CHAR(3) NOT NULL,
    latest_price DECIMAL(19, 4) NULL,
    latest_quote_at DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_stocks_market_symbol UNIQUE (market, symbol),
    CONSTRAINT chk_stocks_latest_price CHECK (latest_price IS NULL OR latest_price > 0),
    CONSTRAINT chk_stocks_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE quote_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(128) NOT NULL,
    stock_id BIGINT UNSIGNED NOT NULL,
    price DECIMAL(19, 4) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    source VARCHAR(32) NOT NULL,
    sequence_no BIGINT UNSIGNED NULL,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    discard_reason VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_quote_events_event_key UNIQUE (event_key),
    CONSTRAINT fk_quote_events_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    CONSTRAINT chk_quote_events_price CHECK (price > 0),
    CONSTRAINT chk_quote_events_status CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'DISCARDED')),
    INDEX idx_quote_events_stock_time (stock_id, event_time, id)
);

CREATE TABLE alert_rules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    stock_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    note VARCHAR(500) NULL,
    direction VARCHAR(8) NOT NULL,
    threshold_price DECIMAL(19, 4) NOT NULL,
    trigger_mode VARCHAR(8) NOT NULL,
    rearm_price DECIMAL(19, 4) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_triggered_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_rules_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_alert_rules_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    CONSTRAINT chk_alert_rules_direction CHECK (direction IN ('ABOVE', 'BELOW')),
    CONSTRAINT chk_alert_rules_threshold CHECK (threshold_price > 0),
    CONSTRAINT chk_alert_rules_mode CHECK (trigger_mode IN ('ONCE', 'REPEAT')),
    CONSTRAINT chk_alert_rules_status CHECK (status IN ('ACTIVE', 'COOLDOWN', 'PAUSED', 'COMPLETED', 'DELETED')),
    CONSTRAINT chk_alert_rules_rearm CHECK (
        (trigger_mode = 'ONCE' AND rearm_price IS NULL)
        OR
        (trigger_mode = 'REPEAT' AND (
            (direction = 'ABOVE' AND rearm_price > 0 AND rearm_price < threshold_price)
            OR
            (direction = 'BELOW' AND rearm_price > threshold_price)
        ))
    ),
    INDEX idx_alert_rules_user_status (user_id, status, id),
    INDEX idx_alert_rules_stock_status (stock_id, status, id)
);

CREATE TABLE alert_triggers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trigger_key VARCHAR(128) NOT NULL,
    rule_id BIGINT UNSIGNED NOT NULL,
    quote_event_id BIGINT UNSIGNED NOT NULL,
    trigger_price DECIMAL(19, 4) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    rule_snapshot JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_alert_triggers_trigger_key UNIQUE (trigger_key),
    CONSTRAINT fk_alert_triggers_rule FOREIGN KEY (rule_id) REFERENCES alert_rules (id),
    CONSTRAINT fk_alert_triggers_quote FOREIGN KEY (quote_event_id) REFERENCES quote_events (id),
    CONSTRAINT chk_alert_triggers_price CHECK (trigger_price > 0),
    INDEX idx_alert_triggers_rule_created (rule_id, created_at, id)
);

CREATE TABLE outbox_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(1000) NULL,
    locked_at DATETIME(6) NULL,
    locked_by VARCHAR(100) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    INDEX idx_outbox_events_dispatch (status, next_retry_at, id),
    INDEX idx_outbox_events_aggregate (aggregate_type, aggregate_id, id)
);

CREATE TABLE notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    trigger_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
    read_at DATETIME(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_notifications_trigger UNIQUE (trigger_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_trigger FOREIGN KEY (trigger_id) REFERENCES alert_triggers (id),
    CONSTRAINT chk_notifications_status CHECK (status IN ('UNREAD', 'READ')),
    INDEX idx_notifications_user_status_created (user_id, status, created_at, id)
);
