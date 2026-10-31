-- Customer e-ticket downloads (spec 2026-10-31-customer-ticket-and-owner-booking-design.md): one row per download with
-- the markup and the fare lines frozen at that moment, so a reopen prints the same price. No PDF and no personal data.
CREATE TABLE platform_booking_ticket (
    id                  VARCHAR(36)   PRIMARY KEY,
    platform_booking_id VARCHAR(36)   NOT NULL REFERENCES platform_booking (id),
    tenant_id           VARCHAR(36)   NOT NULL,
    seq                 INT           NOT NULL,
    markup              NUMERIC(19,2) NOT NULL CHECK (markup >= 0),
    fare_lines          jsonb         NOT NULL,
    total               NUMERIC(19,2) NOT NULL,
    created_by          VARCHAR(36)   NOT NULL,
    created_by_name     VARCHAR(160),
    created_at          TIMESTAMPTZ   NOT NULL,
    UNIQUE (platform_booking_id, seq)
);
CREATE INDEX idx_platform_booking_ticket_booking ON platform_booking_ticket (platform_booking_id);
