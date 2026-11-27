-- A cancelled booking stays open on the Cancellations tab until someone marks its refund processed (client 27 Nov).
ALTER TABLE booking ADD COLUMN refund_processed_at timestamp without time zone;
ALTER TABLE booking ADD COLUMN refund_processed_by character varying(255);

-- Cancellations already settled before this release stay closed, as they were.
UPDATE booking SET refund_processed_at = COALESCE(cancelled_at, now()), refund_processed_by = 'Settled before refund tracking'
WHERE booking_status = 'CANCELLED'
  AND refunded_total_inr >= customer_refund_due
  AND supplier_refund_received >= supplier_refund_expected;
