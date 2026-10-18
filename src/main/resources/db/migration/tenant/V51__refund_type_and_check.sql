-- Cancellation type (void / cancellation / no show / full refund) and the accountant's
-- cross-check once both sides of a cancelled booking's refund are settled.
ALTER TABLE booking
    ADD COLUMN cancellation_type VARCHAR(20),
    ADD COLUMN refund_checked_at TIMESTAMP,
    ADD COLUMN refund_checked_by VARCHAR(255);

-- Cancellations from before refund tracking have nothing left to check.
UPDATE booking
SET refund_checked_at = COALESCE(cancelled_at, now()),
    refund_checked_by = 'Before refund tracking'
WHERE booking_status = 'CANCELLED';
