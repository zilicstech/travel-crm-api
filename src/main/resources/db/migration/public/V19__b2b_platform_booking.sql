-- Platform bookings live in the public schema. No tenant table may be named platform_booking or
-- platform_booking_event: the tenant search_path is "tenant_x, public" and a same-named tenant
-- table would shadow these (scripts/blueprint-audit.sh enforces it).
CREATE TABLE platform_booking (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    product VARCHAR(8) NOT NULL,            -- FLIGHT|HOTEL
    client_id VARCHAR(36), lead_id VARCHAR(36), service_id VARCHAR(36), issued_by_agent_id VARCHAR(36),
    status VARCHAR(20) NOT NULL,            -- REVIEWING|HELD|BOOKING|UNKNOWN|TICKETED|FAILED|CANCELLED|ABORTED
    environment VARCHAR(4) NOT NULL,        -- UAT|PROD
    credential_id VARCHAR(36),
    supplier_review_id VARCHAR(80) UNIQUE,
    supplier_booking_id VARCHAR(80),
    pnr VARCHAR(40),
    ticket_numbers TEXT[],
    gross NUMERIC(19,2), base NUMERIC(19,2), discount_pct NUMERIC(5,2), displayed NUMERIC(19,2),
    pricing_snapshot JSONB,
    voyra_earned NUMERIC(19,2),
    voyra_earned_source VARCHAR(10),        -- API|ESTIMATE|STATEMENT
    voyra_earned_actual NUMERIC(19,2),
    voyra_earned_reversal NUMERIC(19,2),
    agency_commission NUMERIC(19,2), tds NUMERIC(19,2), voyra_net NUMERIC(19,2),
    pax_count INT,
    pax_enc TEXT,                           -- AES-encrypted passenger JSON
    itinerary JSONB,
    hold_entry_id VARCHAR(36), debit_entry_id VARCHAR(36),
    mirror_status VARCHAR(8) NOT NULL DEFAULT 'NONE',   -- NONE|PENDING|DONE|FAILED
    tenant_booking_id VARCHAR(36),
    booked_by VARCHAR(36),
    abort_reason VARCHAR(300),
    supplier_message VARCHAR(500),
    fare_rules TEXT,
    review_expires_at TIMESTAMPTZ,
    cancel_refund NUMERIC(19,2),
    cancelled_at TIMESTAMPTZ,
    last_polled_at TIMESTAMPTZ,
    poll_count INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(80) UNIQUE,     -- the client's key: a double click returns the same booking
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ticketed_at TIMESTAMPTZ
);
CREATE INDEX idx_platform_booking_tenant_created ON platform_booking (tenant_id, created_at DESC);
CREATE INDEX idx_platform_booking_status ON platform_booking (status);

CREATE TABLE platform_booking_event (
    id VARCHAR(36) PRIMARY KEY,
    platform_booking_id VARCHAR(36) NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload JSONB,                          -- redacted: no apikey, passport numbers masked
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_booking_event_booking ON platform_booking_event (platform_booking_id, created_at);

-- A rate that was never approved can be withdrawn; the row stays for the record.
ALTER TABLE agency_pricing ADD COLUMN withdrawn_at TIMESTAMPTZ;
ALTER TABLE agency_pricing ADD COLUMN withdrawn_by VARCHAR(36);

INSERT INTO platform_setting (key, value) VALUES ('voyra_cancel_fee', '0') ON CONFLICT (key) DO NOTHING;
