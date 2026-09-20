-- ---------------------------------------------------------------------------
-- SUPPLIER_PAYMENT - append-only, mirrors payment_receipt. A correction is a
-- reversing row pointing at the original via reverses_payment_id (opposite-
-- signed amount); the original is never updated except to stamp
-- reversed_at/by/reason. applied_from_advance marks a row that only moves a
-- bill's balance (an advance being applied) rather than moving money - the
-- ledger poster skips those, since the advance already posted its debit and
-- the bill already posted its credit (ARCHITECTURE-SPINE AD-5).
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_payment (
    id                      VARCHAR(36) NOT NULL PRIMARY KEY,
    voucher_number          VARCHAR(40),
    financial_year          VARCHAR(9),
    direction               VARCHAR(20) NOT NULL,
    vendor_id               VARCHAR(36) NOT NULL,
    vendor_name             VARCHAR(150) NOT NULL,
    supplier_invoice_id     VARCHAR(36),
    supplier_credit_note_id VARCHAR(36),
    booking_id              VARCHAR(36),
    currency_code           VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr          NUMERIC(18,6) NOT NULL DEFAULT 1.000000,
    amount                  NUMERIC(19,2) NOT NULL DEFAULT 0,
    amount_inr              NUMERIC(19,2) NOT NULL DEFAULT 0,
    tds_withheld            NUMERIC(19,2) NOT NULL DEFAULT 0,
    payment_mode            VARCHAR(20) NOT NULL,
    instrument_ref          VARCHAR(100),
    bank_account_label      VARCHAR(150),
    paid_on                 DATE NOT NULL,
    is_advance              BOOLEAN NOT NULL DEFAULT FALSE,
    applied_from_advance    BOOLEAN NOT NULL DEFAULT FALSE,
    reverses_payment_id     VARCHAR(36),
    reversed_at TIMESTAMP, reversed_by VARCHAR(36), reversal_reason VARCHAR(255),
    notes VARCHAR(500),
    created_at TIMESTAMP, created_by VARCHAR(36)
);
CREATE UNIQUE INDEX idx_sup_pay_number ON supplier_payment (voucher_number) WHERE voucher_number IS NOT NULL;
CREATE INDEX idx_sup_pay_vendor  ON supplier_payment (vendor_id, paid_on DESC);
CREATE INDEX idx_sup_pay_invoice ON supplier_payment (supplier_invoice_id);
CREATE INDEX idx_sup_pay_booking ON supplier_payment (booking_id);
