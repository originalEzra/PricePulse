ALTER TABLE stocks MODIFY COLUMN name VARCHAR(512) NOT NULL,
    ADD COLUMN alpaca_asset_id CHAR(36) NULL,
    ADD UNIQUE KEY uk_stocks_alpaca_asset (alpaca_asset_id);

CREATE TABLE alpaca_assets (
    id CHAR(36) NOT NULL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(512) NOT NULL,
    asset_class VARCHAR(32) NOT NULL,
    exchange VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    tradable BOOLEAN NOT NULL,
    revision CHAR(36) NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_alpaca_asset_symbol (symbol),
    INDEX idx_alpaca_assets_status (status, symbol)
);

CREATE TABLE market_subscription_control (
    id TINYINT NOT NULL PRIMARY KEY,
    symbol_limit INT NOT NULL,
    CONSTRAINT chk_subscription_limit CHECK (symbol_limit BETWEEN 1 AND 10000)
);
INSERT INTO market_subscription_control(id,symbol_limit) VALUES(1,15);

CREATE TABLE market_subscription_targets (
    stock_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    slot_reserved BOOLEAN NOT NULL DEFAULT FALSE,
    legacy_hold BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    confirmed_epoch VARCHAR(96) NULL,
    error_code VARCHAR(64) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_subscription_target_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
    CONSTRAINT chk_subscription_target_state CHECK (state IN ('PENDING','CONFIRMED','FAILED','CANCELLED'))
);

CREATE TABLE user_stock_subscriptions (
    user_id BIGINT UNSIGNED NOT NULL,
    stock_id BIGINT UNSIGNED NOT NULL,
    desired BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id,stock_id),
    CONSTRAINT fk_user_stock_subscription_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_stock_subscription_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
    INDEX idx_user_stock_subscription_demand (stock_id,desired,user_id)
);

INSERT INTO market_subscription_targets(stock_id,legacy_hold,slot_reserved)
SELECT id,TRUE,TRUE FROM stocks WHERE market='US' AND status='ACTIVE'
  AND symbol IN ('AAPL','MSFT','NVDA','AMZN','GOOGL','ORCL','SMCI','SOFI','AMD','TSLA','CRWD','MU','INTC','BA','HOOD');

-- One-time compatibility demand; no rule/price/history changes or fabricated acknowledgement.
INSERT INTO user_stock_subscriptions(user_id,stock_id)
SELECT u.id,t.stock_id FROM users u CROSS JOIN market_subscription_targets t;
