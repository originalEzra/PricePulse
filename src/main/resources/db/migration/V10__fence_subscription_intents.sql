-- Additive only. Observations advance version; intent_version changes only for a new demand.
ALTER TABLE market_subscription_targets ADD COLUMN intent_version BIGINT UNSIGNED NOT NULL DEFAULT 0;
