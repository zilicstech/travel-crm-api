-- ---------------------------------------------------------------------------
-- CUSTOMER_LEDGER_ENTRY - append-only subsidiary receivables ledger, one row
-- per debit or credit event against a client. CustomerLedgerService is the
-- sole writer, posting inside the same transaction as the invoice/receipt/
-- credit-note write that caused it - a rollback drops both together. The
-- unique index on (source_type, source_id, entry_type) makes a duplicate
-- post a constraint violation rather than a silent double-count.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_ledger_entry (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    client_id         VARCHAR(36)  NOT NULL,
    entry_date        DATE         NOT NULL,
    entry_type        VARCHAR(30)  NOT NULL,
    source_type       VARCHAR(30)  NOT NULL,
    source_id         VARCHAR(36)  NOT NULL,
    document_number   VARCHAR(40),
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
CREATE UNIQUE INDEX idx_ledger_source ON customer_ledger_entry (source_type, source_id, entry_type);
CREATE INDEX idx_ledger_client ON customer_ledger_entry (client_id, entry_date, created_at);
