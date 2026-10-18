-- One-step booking cancellation: what the agency keeps and what each side still owes.
-- Customer refunded so far stays in refunded_total_inr (synced from REFUND receipts).
ALTER TABLE booking
    ADD COLUMN cancellation_supplier_charge NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN cancellation_agency_fee      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN customer_refund_due          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_refund_expected     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_refund_received     NUMERIC(19, 2) NOT NULL DEFAULT 0;
