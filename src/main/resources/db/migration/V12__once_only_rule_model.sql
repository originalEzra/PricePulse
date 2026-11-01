-- Fail closed BEFORE removing any legacy column. Never convert, delete or disable a legacy rule.
-- The atomic validation also prevents a concurrent old writer slipping through a SELECT precheck.
ALTER TABLE alert_rules
    ADD CONSTRAINT chk_alert_rules_once_only_preflight CHECK (
        trigger_mode='ONCE' AND rearm_price IS NULL AND status IN ('ACTIVE','COMPLETED','DELETED')),
    ADD CONSTRAINT chk_alert_rules_once_status CHECK (status IN ('ACTIVE','COMPLETED','DELETED'));

-- All rows are now proven once-only. IDs, versions, prices, timestamps and history remain unchanged.
ALTER TABLE alert_rules
    DROP CHECK chk_alert_rules_once_only_preflight,
    DROP CHECK chk_alert_rules_mode,
    DROP CHECK chk_alert_rules_rearm,
    DROP CHECK chk_alert_rules_status,
    DROP COLUMN trigger_mode,
    DROP COLUMN rearm_price;
