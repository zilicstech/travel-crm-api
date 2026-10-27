-- Set when a platform admin resolves a booking as FAILED: the poller keeps asking the supplier for 72 hours after,
-- so a ticket issued anyway is still debited (spec 7.3, late ticket).
ALTER TABLE platform_booking ADD COLUMN resolved_failed_at TIMESTAMPTZ;
