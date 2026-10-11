-- Decision 5 (ACCOUNTING_EXPANSION_ARCHITECTURE.md) - gateway fee capture. No new PaymentMode:
-- a gateway payment is still a CARD/UPI/BANK_TRANSFER instrument, the provider is orthogonal
-- (Rule 5.1). All nullable - the common, non-gateway receipt is unaffected.

ALTER TABLE payment_receipt ADD COLUMN gateway_provider VARCHAR(30);
ALTER TABLE payment_receipt ADD COLUMN gateway_txn_ref VARCHAR(80);
ALTER TABLE payment_receipt ADD COLUMN gateway_fee NUMERIC(19,2);
ALTER TABLE payment_receipt ADD COLUMN gateway_fee_inr NUMERIC(19,2);
ALTER TABLE payment_receipt ADD COLUMN net_deposit_inr NUMERIC(19,2);
