-- Customer wallet: a receipt can now be recorded with no invoice at all (a deposit on
-- account), and applied to an invoice later without moving money twice. invoice_id was
-- already nullable; only the "this receipt settled a bill by drawing on an existing advance,
-- not new money" flag is new - mirrors supplier_payment.applied_from_advance (V27).

ALTER TABLE payment_receipt ADD COLUMN applied_from_advance BOOLEAN NOT NULL DEFAULT FALSE;
