-- ---------------------------------------------------------------------------
-- PAYMENT_RECEIPT - append-only. A correction is a reversing row pointing at
-- the original via reverses_receipt_id (opposite-signed amount); the original
-- is never updated except to stamp reversed_at/reversed_by/reversal_reason.
-- direction is RECEIPT only in this epic - REFUND rows are written starting
-- with credit notes (Epic 6). currency_code/fx_rate_to_inr are always copied
-- from the invoice, never entered independently - a payment in a currency
-- other than the invoice's is rejected, never converted (see PaymentReceiptService).
-- ---------------------------------------------------------------------------
CREATE TABLE payment_receipt (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    receipt_number      VARCHAR(40),
    financial_year      VARCHAR(9),
    direction           VARCHAR(10) NOT NULL,
    invoice_id          VARCHAR(36),
    credit_note_id      VARCHAR(36),
    client_id           VARCHAR(36) NOT NULL,
    booking_id          VARCHAR(36),
    currency_code       VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr      NUMERIC(18,6) NOT NULL DEFAULT 1.000000,
    amount              NUMERIC(19,2) NOT NULL DEFAULT 0,
    amount_inr          NUMERIC(19,2) NOT NULL DEFAULT 0,
    payment_mode        VARCHAR(20) NOT NULL,
    instrument_ref      VARCHAR(100),
    bank_account_label  VARCHAR(150),
    received_on         DATE NOT NULL,
    is_advance          BOOLEAN NOT NULL DEFAULT FALSE,
    reverses_receipt_id VARCHAR(36),
    reversed_at         TIMESTAMP,
    reversed_by         VARCHAR(36),
    reversal_reason     VARCHAR(255),
    notes               VARCHAR(500),
    created_at TIMESTAMP, created_by VARCHAR(36)
);
CREATE UNIQUE INDEX idx_receipt_number ON payment_receipt (receipt_number) WHERE receipt_number IS NOT NULL;
CREATE INDEX idx_receipt_invoice ON payment_receipt (invoice_id);
CREATE INDEX idx_receipt_client  ON payment_receipt (client_id, received_on DESC);
CREATE INDEX idx_receipt_booking ON payment_receipt (booking_id);
CREATE INDEX idx_receipt_cn      ON payment_receipt (credit_note_id);
