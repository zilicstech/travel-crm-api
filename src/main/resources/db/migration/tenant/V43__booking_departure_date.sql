-- Canonical departure date (ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 2.2.1): the revenue
-- recognition job queries this one column across every booking type without branching on type.
-- Nullable - a booking with no derivable date recognises immediately (Rule 2.2.2), never blocked.

ALTER TABLE booking ADD COLUMN departure_date DATE;

CREATE INDEX idx_booking_departure_date ON booking (departure_date);
