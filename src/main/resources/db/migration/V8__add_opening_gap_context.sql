-- Keep rule activation/configuration time independent of matcher updates.
ALTER TABLE alert_rules ADD COLUMN effective_at DATETIME(6) NULL;
UPDATE alert_rules SET effective_at=updated_at, updated_at=updated_at;
ALTER TABLE alert_rules MODIFY COLUMN effective_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- Existing triggers and their immutable rule snapshots remain unchanged.
ALTER TABLE alert_triggers ADD COLUMN trigger_context JSON NULL;
