-- ---------------------------------------------------------------------------
-- SUPPLIER_LEDGER_ENTRY - append-only subsidiary payables ledger, one row per
-- debit or credit event against a vendor. Column-for-column the mirror of
-- customer_ledger_entry (V21), including the idempotency index - see
-- SupplierLedgerService, the sole writer, posting inside the same
-- transaction as the bill/payment/credit-note write that caused it.
--
-- Sign convention (ARCHITECTURE-SPINE AD-4): CREDIT raises what we owe
-- (a bill booked, a credit note reversed); DEBIT lowers it (a payment made,
-- an advance paid). balance = SUM(credit) - SUM(debit): positive = we owe
-- the vendor, negative = the vendor holds our deposit.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_ledger_entry (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    vendor_id         VARCHAR(36)  NOT NULL,
    entry_date        DATE         NOT NULL,
    entry_type        VARCHAR(30)  NOT NULL,
    source_type       VARCHAR(30)  NOT NULL,
    source_id         VARCHAR(36)  NOT NULL,
    document_number   VARCHAR(60),
    narration         VARCHAR(255) NOT NULL,
    booking_id        VARCHAR(36),
    currency_code     VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr    NUMERIC(18,6) NOT NULL DEFAULT 1.000000,
    debit_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit_amount     NUMERIC(19,2) NOT NULL DEFAULT 0,
    debit_amount_inr  NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit_amount_inr NUMERIC(19,2) NOT NULL DEFAULT 0,
    reversed_entry_id VARCHAR(36),
    created_at TIMESTAMP, created_by VARCHAR(36)
);
CREATE UNIQUE INDEX idx_sup_ledger_source ON supplier_ledger_entry (source_type, source_id, entry_type);
CREATE INDEX idx_sup_ledger_vendor ON supplier_ledger_entry (vendor_id, entry_date, created_at);
