ALTER TABLE booking ADD COLUMN primary_invoice_id    VARCHAR(36);
ALTER TABLE booking ADD COLUMN invoiced_total_inr    NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE booking ADD COLUMN received_total_inr    NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE booking ADD COLUMN refunded_total_inr    NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE booking ADD COLUMN payment_status_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

CREATE INDEX idx_booking_payment_source ON booking (payment_status_source);
