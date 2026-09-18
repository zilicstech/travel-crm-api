-- Booking.refundStatus was free text ("Refunded ₹62,000"), writable only inside the CANCELLED
-- branch of the status patch, with no REFUND_PENDING state and nothing rolling pending refunds
-- up anywhere. Replace it with a real state machine. refund_status is kept for one release
-- (read-only from here) so nothing breaks mid-migration; a later migration drops it.
ALTER TABLE booking ADD COLUMN refund_state    VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE';
ALTER TABLE booking ADD COLUMN refund_amount   NUMERIC(19,2);
ALTER TABLE booking ADD COLUMN refund_due_date DATE;
ALTER TABLE booking ADD COLUMN refunded_at     TIMESTAMP;
ALTER TABLE booking ADD COLUMN cancelled_at    TIMESTAMP;
ALTER TABLE booking ADD COLUMN cancelled_by    VARCHAR(36);

-- Best-effort backfill from the old free-text column: a cancelled booking whose refund_status
-- already mentions "refund" is treated as REFUNDED; a cancelled booking with no refund_status
-- yet is exactly the REFUND_PENDING case this migration exists to make trackable; anything not
-- cancelled stays NOT_APPLICABLE (the column default already covers it).
UPDATE booking
SET refund_state = CASE
    WHEN booking_status = 'CANCELLED' AND refund_status ILIKE '%refund%' THEN 'REFUNDED'
    WHEN booking_status = 'CANCELLED' THEN 'REFUND_PENDING'
    ELSE 'NOT_APPLICABLE'
END,
    cancelled_at = CASE WHEN booking_status = 'CANCELLED' THEN created_date ELSE NULL END
WHERE booking_status = 'CANCELLED';

CREATE INDEX idx_booking_refund_state ON booking (refund_state) WHERE refund_state != 'NOT_APPLICABLE';

-- No deadline field of any kind existed on Booking before this. Nothing pushes on these - they
-- are surfaced as a pill, a dashboard panel and a filter, all in-app.
ALTER TABLE booking ADD COLUMN ticketing_deadline    DATE;
ALTER TABLE booking ADD COLUMN cancellation_deadline DATE;
ALTER TABLE booking ADD COLUMN deadline_note         VARCHAR(255);
