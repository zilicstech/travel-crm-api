-- Tax becomes opt-in per invoice instead of forced by supply_nature. An invoice now carries
-- zero or more chosen taxes; grand_total always includes them, visible_to_customer controls
-- only the printed presentation (see InvoicePdfRenderer / InvoiceDocumentService).

ALTER TABLE invoice ALTER COLUMN supply_nature DROP NOT NULL;
ALTER TABLE invoice ALTER COLUMN tax_treatment DROP NOT NULL;
ALTER TABLE invoice ALTER COLUMN place_of_supply_code DROP NOT NULL;

ALTER TABLE invoice ADD COLUMN other_tax_total NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE invoice ADD COLUMN other_tax_total_inr NUMERIC(19,2) NOT NULL DEFAULT 0;

CREATE TABLE invoice_tax (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    invoice_id VARCHAR(36) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    label VARCHAR(150) NOT NULL,
    tax_rate_config_id VARCHAR(36),
    tax_kind VARCHAR(10),
    mode VARCHAR(10) NOT NULL,
    rate_percent NUMERIC(6,3),
    flat_amount NUMERIC(19,2),
    cgst_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    visible_to_customer BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE INDEX idx_invoice_tax_invoice ON invoice_tax (invoice_id, sort_order);
