-- ---------------------------------------------------------------------------
-- SUPPLIER_CREDIT_NOTE - the document a supplier issues TO US (cancellation
-- refund, rate correction). Carries the supplier's own note number, not ours
-- (ARCHITECTURE-SPINE AD-7). No child line table, same call as credit_note
-- (V22). retention_fee is the supplier's own cancellation charge, held back
-- from the credit exactly as credit_note.cancellation_fee is on the AR side.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_credit_note (
    id                       VARCHAR(36)  NOT NULL PRIMARY KEY,
    supplier_note_number     VARCHAR(60),
    vendor_id                VARCHAR(36)  NOT NULL,
    vendor_name               VARCHAR(150) NOT NULL,
    supplier_invoice_id      VARCHAR(36)  NOT NULL,
    supplier_invoice_number  VARCHAR(60),
    booking_id               VARCHAR(36),
    reason                   VARCHAR(30)  NOT NULL,
    reason_note              VARCHAR(500),
    status                   VARCHAR(20)  NOT NULL,
    currency_code            VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr           NUMERIC(18,6) NOT NULL DEFAULT 1.000000,
    taxable_value            NUMERIC(19,2) NOT NULL DEFAULT 0,
    cgst_amount              NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount              NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount              NUMERIC(19,2) NOT NULL DEFAULT 0,
    retention_fee            NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_amount             NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_amount_inr         NUMERIC(19,2) NOT NULL DEFAULT 0,
    refundable_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    refunded_amount          NUMERIC(19,2) NOT NULL DEFAULT 0,
    note_date DATE NOT NULL,
    recorded_at TIMESTAMP, recorded_by VARCHAR(36),
    cancelled_at TIMESTAMP, cancelled_by VARCHAR(36), cancel_reason VARCHAR(255),
    file_key VARCHAR(500), file_name VARCHAR(255), content_type VARCHAR(150),
    created_at TIMESTAMP, created_by VARCHAR(36), updated_at TIMESTAMP, updated_by VARCHAR(36)
);
CREATE INDEX idx_sup_cn_vendor  ON supplier_credit_note (vendor_id);
CREATE INDEX idx_sup_cn_invoice ON supplier_credit_note (supplier_invoice_id);
