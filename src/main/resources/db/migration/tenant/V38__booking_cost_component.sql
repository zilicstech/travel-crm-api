-- A booking's net cost broken into its individual supplier components (DMC, airline, hotel, ...),
-- so one booking can fan out into one supplier bill per vendor instead of a single blended cost.
-- See ACCOUNTING_EXPANSION_ARCHITECTURE.md Decision 7. A booking with no rows here keeps today's
-- single-vendor behaviour exactly as it is (booking.vendor_id / booking.net_cost, unchanged).

CREATE TABLE booking_cost_component (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    booking_id           VARCHAR(36) NOT NULL,
    booking_passenger_id VARCHAR(36),
    service_type         VARCHAR(20) NOT NULL,
    vendor_id            VARCHAR(36),
    vendor_name          VARCHAR(120),
    description          VARCHAR(200),
    net_cost             NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3) NOT NULL DEFAULT 'INR',
    fx_rate_to_inr       NUMERIC(18, 6) NOT NULL DEFAULT 1,
    net_cost_inr         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    due_date             DATE,
    sort_order           INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMP,
    created_by           VARCHAR(36)
);

CREATE INDEX idx_booking_cost_component_booking ON booking_cost_component (booking_id);
CREATE INDEX idx_booking_cost_component_vendor ON booking_cost_component (vendor_id);
