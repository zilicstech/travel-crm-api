-- ---------------------------------------------------------------------------
-- DOCUMENT_NUMBER_SEQUENCE - one row per (document_kind, financial_year),
-- incremented atomically via "UPDATE ... RETURNING last_value" so concurrent
-- issues on the same series serialize on the row lock. See DocumentNumberService.
-- ---------------------------------------------------------------------------
CREATE TABLE document_number_sequence (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    document_kind  VARCHAR(20) NOT NULL,
    financial_year VARCHAR(9)  NOT NULL,
    prefix         VARCHAR(12) NOT NULL,
    last_value     BIGINT      NOT NULL DEFAULT 0,
    padding        INTEGER     NOT NULL DEFAULT 4,
    updated_at     TIMESTAMP
);
CREATE UNIQUE INDEX idx_doc_number_seq_key ON document_number_sequence (document_kind, financial_year);

-- ---------------------------------------------------------------------------
-- INVOICE - one booking, one live tax invoice. A number is allocated only at
-- the DRAFT -> ISSUED transition (invoice_number stays NULL while draft), so a
-- deleted draft never burns a number and the series stays gap-free. Recipient
-- and supplier fields are a point-in-time snapshot, not a join, so a reprint
-- years later never depends on today's client/tenant row.
-- ---------------------------------------------------------------------------
CREATE TABLE invoice (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    invoice_number        VARCHAR(40),
    financial_year        VARCHAR(9),
    document_type         VARCHAR(20) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    client_id             VARCHAR(36)  NOT NULL,
    client_name           VARCHAR(150) NOT NULL,
    client_gstin          VARCHAR(20),
    client_state_code     VARCHAR(2),
    billing_address       VARCHAR(500),
    agency_legal_name     VARCHAR(200),
    agency_gstin          VARCHAR(20),
    agency_state_code     VARCHAR(2),
    agency_address        VARCHAR(500),
    booking_id            VARCHAR(36),
    lead_id               VARCHAR(36),
    agent_id              VARCHAR(36) NOT NULL,
    place_of_supply_code  VARCHAR(2)  NOT NULL,
    supply_nature         VARCHAR(30) NOT NULL,
    tax_treatment         VARCHAR(20) NOT NULL,
    place_of_supply_override VARCHAR(2),
    export_of_service_requested BOOLEAN NOT NULL DEFAULT FALSE,
    currency_code         VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr        NUMERIC(18,6) NOT NULL DEFAULT 1.000000,
    fx_rate_source        VARCHAR(30),
    fx_locked_at          TIMESTAMP,
    subtotal              NUMERIC(19,2) NOT NULL DEFAULT 0,
    discount_total        NUMERIC(19,2) NOT NULL DEFAULT 0,
    taxable_value         NUMERIC(19,2) NOT NULL DEFAULT 0,
    cgst_amount           NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount           NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount           NUMERIC(19,2) NOT NULL DEFAULT 0,
    gst_total             NUMERIC(19,2) NOT NULL DEFAULT 0,
    tcs_rate_percent      NUMERIC(6,3)  NOT NULL DEFAULT 0,
    tcs_section           VARCHAR(20),
    tcs_base_amount       NUMERIC(19,2) NOT NULL DEFAULT 0,
    tcs_amount            NUMERIC(19,2) NOT NULL DEFAULT 0,
    round_off             NUMERIC(19,2) NOT NULL DEFAULT 0,
    grand_total           NUMERIC(19,2) NOT NULL DEFAULT 0,
    taxable_value_inr     NUMERIC(19,2) NOT NULL DEFAULT 0,
    gst_total_inr         NUMERIC(19,2) NOT NULL DEFAULT 0,
    tcs_amount_inr        NUMERIC(19,2) NOT NULL DEFAULT 0,
    grand_total_inr       NUMERIC(19,2) NOT NULL DEFAULT 0,
    amount_received       NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit_note_total     NUMERIC(19,2) NOT NULL DEFAULT 0,
    balance_due           NUMERIC(19,2) NOT NULL DEFAULT 0,
    balance_due_inr       NUMERIC(19,2) NOT NULL DEFAULT 0,
    invoice_date          DATE,
    due_date              DATE,
    issued_at             TIMESTAMP,
    issued_by             VARCHAR(36),
    cancelled_at          TIMESTAMP,
    cancelled_by          VARCHAR(36),
    cancel_reason         VARCHAR(255),
    supersedes_invoice_id VARCHAR(36),
    notes                 VARCHAR(1000),
    terms                 VARCHAR(2000),
    pdf_file_key          VARCHAR(500),
    created_at TIMESTAMP, created_by VARCHAR(36),
    updated_at TIMESTAMP, updated_by VARCHAR(36)
);
CREATE UNIQUE INDEX idx_invoice_number ON invoice (invoice_number) WHERE invoice_number IS NOT NULL;
CREATE INDEX idx_invoice_client  ON invoice (client_id);
CREATE INDEX idx_invoice_booking ON invoice (booking_id);
CREATE INDEX idx_invoice_agent   ON invoice (agent_id);
CREATE INDEX idx_invoice_status  ON invoice (status);
CREATE INDEX idx_invoice_date    ON invoice (invoice_date);
-- One live (non-cancelled) tax-invoice-track row per booking - a DRAFT already occupies the
-- slot, so a second draft against the same booking is rejected before it can compete with it.
CREATE UNIQUE INDEX idx_invoice_booking_live ON invoice (booking_id)
    WHERE booking_id IS NOT NULL AND document_type = 'TAX_INVOICE' AND status <> 'CANCELLED';

CREATE TABLE invoice_line_item (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    invoice_id         VARCHAR(36)  NOT NULL,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    description        VARCHAR(500) NOT NULL,
    sac_code           VARCHAR(10),
    service_type       VARCHAR(30),
    quantity           NUMERIC(12,3) NOT NULL DEFAULT 1,
    unit_price         NUMERIC(19,2) NOT NULL DEFAULT 0,
    line_subtotal      NUMERIC(19,2) NOT NULL DEFAULT 0,
    discount_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    taxable_percent    NUMERIC(6,3)  NOT NULL DEFAULT 100.000,
    taxable_value      NUMERIC(19,2) NOT NULL DEFAULT 0,
    gst_rate_percent   NUMERIC(6,3)  NOT NULL DEFAULT 0,
    cgst_rate_percent  NUMERIC(6,3)  NOT NULL DEFAULT 0,
    sgst_rate_percent  NUMERIC(6,3)  NOT NULL DEFAULT 0,
    igst_rate_percent  NUMERIC(6,3)  NOT NULL DEFAULT 0,
    cgst_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    line_total         NUMERIC(19,2) NOT NULL DEFAULT 0,
    tax_rate_config_id VARCHAR(36),
    created_at TIMESTAMP
);
CREATE INDEX idx_invoice_line_invoice ON invoice_line_item (invoice_id, sort_order);
CREATE INDEX idx_invoice_line_sac     ON invoice_line_item (sac_code, gst_rate_percent);
