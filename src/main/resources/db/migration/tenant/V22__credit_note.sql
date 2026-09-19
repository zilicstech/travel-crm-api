CREATE TABLE credit_note (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    credit_note_number VARCHAR(40),
    financial_year     VARCHAR(9),
    invoice_id     VARCHAR(36)  NOT NULL,
    invoice_number VARCHAR(40),
    client_id      VARCHAR(36)  NOT NULL,
    client_name    VARCHAR(150) NOT NULL,
    booking_id     VARCHAR(36),
    reason         VARCHAR(30)  NOT NULL,
    reason_note    VARCHAR(500),
    status         VARCHAR(20)  NOT NULL,
    currency_code  VARCHAR(3)    NOT NULL,
    fx_rate_to_inr NUMERIC(18,6) NOT NULL,
    taxable_value    NUMERIC(19,2) NOT NULL DEFAULT 0,
    cgst_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    tcs_amount       NUMERIC(19,2) NOT NULL DEFAULT 0,
    cancellation_fee NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_amount_inr  NUMERIC(19,2) NOT NULL DEFAULT 0,
    refundable_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    refunded_amount   NUMERIC(19,2) NOT NULL DEFAULT 0,
    note_date DATE NOT NULL,
    issued_at TIMESTAMP, issued_by VARCHAR(36),
    cancelled_at TIMESTAMP, cancelled_by VARCHAR(36), cancel_reason VARCHAR(255),
    pdf_file_key VARCHAR(500),
    created_at TIMESTAMP, created_by VARCHAR(36),
    updated_at TIMESTAMP, updated_by VARCHAR(36)
);
CREATE UNIQUE INDEX idx_cn_number ON credit_note (credit_note_number) WHERE credit_note_number IS NOT NULL;
CREATE INDEX idx_cn_invoice ON credit_note (invoice_id);
CREATE INDEX idx_cn_client  ON credit_note (client_id);
