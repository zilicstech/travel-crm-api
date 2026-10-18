-- Bookings start as PENDING with basic details, so a searched flight's clock times are kept
-- ('HH:mm' local strings, the same convention as transfer_time).
ALTER TABLE booking ADD COLUMN IF NOT EXISTS flight_depart_time VARCHAR(5);
ALTER TABLE booking ADD COLUMN IF NOT EXISTS flight_arrive_time VARCHAR(5);
ALTER TABLE booking ADD COLUMN IF NOT EXISTS flight_arrive_date DATE;

-- An agent's "supplier refund came into our bank" is a claim until the accountant finds it on the
-- bank statement and confirms it - only that confirm posts to Bank. One open claim at a time.
ALTER TABLE booking ADD COLUMN IF NOT EXISTS supplier_refund_claim_amount NUMERIC(14,2);
ALTER TABLE booking ADD COLUMN IF NOT EXISTS supplier_refund_claim_on DATE;
ALTER TABLE booking ADD COLUMN IF NOT EXISTS supplier_refund_claim_reference VARCHAR(100);
ALTER TABLE booking ADD COLUMN IF NOT EXISTS supplier_refund_claim_by VARCHAR(150);
ALTER TABLE booking ADD COLUMN IF NOT EXISTS supplier_refund_claim_at TIMESTAMP;
