-- Incremental: existing quotes retain their origin and are never replayed into matching.
ALTER TABLE quote_events
    ADD COLUMN event_time_ns BIGINT NULL,
    ADD COLUMN provider VARCHAR(24) NULL,
    ADD COLUMN feed VARCHAR(24) NULL,
    ADD COLUMN accepted_sequence BIGINT UNSIGNED NULL,
    ADD COLUMN processed_at DATETIME(6) NULL,
    ADD COLUMN baseline_reason VARCHAR(64) NULL,
    ADD INDEX idx_quote_real_timeline (stock_id, origin_type, provider, feed, processing_status, event_time_ns, id);

-- Serialized with the existing stocks FOR UPDATE lock. History sync never touches this row.
CREATE TABLE market_stream_state (
    stock_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    feed VARCHAR(24) NOT NULL,
    event_time_ns BIGINT NOT NULL,
    accepted_sequence BIGINT UNSIGNED NOT NULL,
    continuity_id VARCHAR(64) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_market_stream_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE market_sessions (
    provider VARCHAR(24) NOT NULL,
    session_date DATE NOT NULL,
    opens_at DATETIME(6) NOT NULL,
    closes_at DATETIME(6) NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider, session_date),
    CONSTRAINT chk_market_session_time CHECK (closes_at > opens_at)
);

CREATE TABLE market_price_bars (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(24) NOT NULL,
    feed VARCHAR(24) NOT NULL,
    resolution VARCHAR(8) NOT NULL,
    adjustment VARCHAR(8) NOT NULL,
    interval_start DATETIME(6) NOT NULL,
    interval_end DATETIME(6) NOT NULL,
    open_price DECIMAL(19,4) NOT NULL,
    high_price DECIMAL(19,4) NOT NULL,
    low_price DECIMAL(19,4) NOT NULL,
    close_price DECIMAL(19,4) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    fetched_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_market_bar_source (stock_id, provider, feed, resolution, adjustment, interval_start),
    INDEX idx_market_bar_timeline (stock_id, provider, feed, interval_end, id),
    -- Catalog IDs are validated by the server before downloading. No stock FK: cache warming
    -- must not acquire shared parent locks that block the live stocks FOR UPDATE transaction.
    CONSTRAINT chk_market_bar_prices CHECK (low_price > 0 AND high_price >= low_price
        AND open_price BETWEEN low_price AND high_price AND close_price BETWEEN low_price AND high_price),
    CONSTRAINT chk_market_bar_interval CHECK (interval_end > interval_start)
);

CREATE TABLE market_history_coverage (
    stock_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(24) NOT NULL,
    feed VARCHAR(24) NOT NULL,
    requested_from DATETIME(6) NOT NULL,
    requested_to DATETIME(6) NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    PRIMARY KEY (stock_id, provider, feed)
);

INSERT INTO stocks (symbol,name,market,currency,status) VALUES
    ('AAPL','Apple','US','USD','ACTIVE'),
    ('MSFT','Microsoft','US','USD','ACTIVE'),
    ('NVDA','NVIDIA','US','USD','ACTIVE'),
    ('AMZN','Amazon','US','USD','ACTIVE'),
    ('GOOGL','Alphabet','US','USD','ACTIVE')
ON DUPLICATE KEY UPDATE symbol=stocks.symbol;
