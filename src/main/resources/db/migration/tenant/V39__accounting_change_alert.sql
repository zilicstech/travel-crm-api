-- An accountant-facing queue of itinerary-relevant edits made to a booking AFTER it already has a
-- live invoice (or is CONFIRMED) against it. Separate from the audit log - audit_log answers "what
-- changed and when" for any booking; this table answers "what still needs an accountant's review"
-- and is acknowledged/cleared independently. See ACCOUNTING_EXPANSION_IMPLEMENTATION_PLAN.md Epic B.

CREATE TABLE accounting_change_alert (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    booking_id      VARCHAR(36)  NOT NULL,
    field_name      VARCHAR(60)  NOT NULL,
    old_value       VARCHAR(500),
    new_value       VARCHAR(500),
    raised_at       TIMESTAMP    NOT NULL,
    raised_by       VARCHAR(36),
    acknowledged    BOOLEAN      NOT NULL DEFAULT FALSE,
    acknowledged_at TIMESTAMP,
    acknowledged_by VARCHAR(36)
);

CREATE INDEX idx_accounting_change_alert_booking ON accounting_change_alert (booking_id);
CREATE INDEX idx_accounting_change_alert_unacked ON accounting_change_alert (acknowledged, raised_at);
