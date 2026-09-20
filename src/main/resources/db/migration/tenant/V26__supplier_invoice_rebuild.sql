-- ---------------------------------------------------------------------------
-- SUPPLIER_INVOICE rebuild - the V1 stub (supplier_name, category, amount,
-- status, due_date, booking_ref) becomes a real accounts-payable document,
-- in place rather than as a new table: existing rows are referenced by
-- audit_log (AuditEntityType.SUPPLIER_INVOICE), so an in-place backfill
-- keeps that history resolvable, the way V11 migrated suppliers in place.
--
-- GST here is RECORDED, never computed (unlike invoice/TaxEngine on the AR
-- side) - the supplier has already done their own arithmetic; we only
-- validate it agrees with vendor.state_code vs the agency's, in
-- SupplierInvoiceService. See ARCHITECTURE-SPINE AD-2.
-- ---------------------------------------------------------------------------

-- identity & classification
ALTER TABLE supplier_invoice ADD COLUMN vendor_id VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PURCHASE';
ALTER TABLE supplier_invoice ADD COLUMN supplier_invoice_number VARCHAR(60);
ALTER TABLE supplier_invoice ADD COLUMN supplier_gstin VARCHAR(20);
ALTER TABLE supplier_invoice ADD COLUMN supplier_state_code VARCHAR(2);
ALTER TABLE supplier_invoice ADD COLUMN invoice_date DATE;
ALTER TABLE supplier_invoice ADD COLUMN received_on DATE;
ALTER TABLE supplier_invoice ADD COLUMN booking_id VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN lead_id VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN service_type VARCHAR(20);
ALTER TABLE supplier_invoice ADD COLUMN reference_note VARCHAR(255);

-- currency
ALTER TABLE supplier_invoice ADD COLUMN currency_code  VARCHAR(3)    NOT NULL DEFAULT 'INR';
ALTER TABLE supplier_invoice ADD COLUMN fx_rate_to_inr NUMERIC(18,6) NOT NULL DEFAULT 1.000000;
ALTER TABLE supplier_invoice ADD COLUMN fx_rate_source VARCHAR(30);

-- recorded tax
ALTER TABLE supplier_invoice ADD COLUMN subtotal        NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN discount_total  NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN taxable_value   NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN cgst_amount     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN sgst_amount     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN igst_amount     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN gst_total       NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN cess_amount     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN round_off       NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN grand_total     NUMERIC(19,2) NOT NULL DEFAULT 0;

-- input tax credit + reverse charge + TDS
ALTER TABLE supplier_invoice ADD COLUMN itc_eligibility   VARCHAR(20) NOT NULL DEFAULT 'ELIGIBLE';
ALTER TABLE supplier_invoice ADD COLUMN itc_note          VARCHAR(255);
ALTER TABLE supplier_invoice ADD COLUMN is_reverse_charge BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE supplier_invoice ADD COLUMN tds_section       VARCHAR(20);
ALTER TABLE supplier_invoice ADD COLUMN tds_rate_percent  NUMERIC(6,3)  NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN tds_amount        NUMERIC(19,2) NOT NULL DEFAULT 0;

-- INR mirrors
ALTER TABLE supplier_invoice ADD COLUMN taxable_value_inr NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN gst_total_inr     NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN grand_total_inr   NUMERIC(19,2) NOT NULL DEFAULT 0;

-- settlement
ALTER TABLE supplier_invoice ADD COLUMN amount_paid       NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN credit_note_total NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN balance_due       NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE supplier_invoice ADD COLUMN balance_due_inr   NUMERIC(19,2) NOT NULL DEFAULT 0;

-- lifecycle + document
ALTER TABLE supplier_invoice ADD COLUMN approved_at TIMESTAMP;
ALTER TABLE supplier_invoice ADD COLUMN approved_by VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE supplier_invoice ADD COLUMN cancelled_by VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN cancel_reason VARCHAR(255);
ALTER TABLE supplier_invoice ADD COLUMN notes VARCHAR(1000);
ALTER TABLE supplier_invoice ADD COLUMN file_key  VARCHAR(500);
ALTER TABLE supplier_invoice ADD COLUMN file_name VARCHAR(255);
ALTER TABLE supplier_invoice ADD COLUMN content_type VARCHAR(150);

-- Backfill from the four legacy columns before they're dropped. A legacy row whose
-- supplier_name matches no vendor (case-insensitively) keeps vendor_id NULL and is
-- surfaced in the UI as "unlinked" for an accountant to repoint - never auto-create a
-- vendor from a stale string.
UPDATE supplier_invoice si SET
    vendor_id       = v.id,
    grand_total     = si.amount,
    grand_total_inr = si.amount,
    taxable_value   = si.amount,
    taxable_value_inr = si.amount,
    balance_due     = CASE WHEN si.status = 'PAID' THEN 0 ELSE si.amount END,
    balance_due_inr = CASE WHEN si.status = 'PAID' THEN 0 ELSE si.amount END,
    amount_paid     = CASE WHEN si.status = 'PAID' THEN si.amount ELSE 0 END,
    booking_id      = si.booking_ref,
    invoice_date    = si.created_date::date,
    received_on     = si.created_date::date,
    status          = CASE WHEN si.status = 'PAID' THEN 'PAID' ELSE 'APPROVED' END
FROM vendor v
WHERE LOWER(v.name) = LOWER(si.supplier_name);

UPDATE supplier_invoice SET status = 'APPROVED', grand_total = amount, grand_total_inr = amount,
    taxable_value = amount, taxable_value_inr = amount, balance_due = amount, balance_due_inr = amount,
    invoice_date = created_date::date, received_on = created_date::date, booking_id = booking_ref
WHERE vendor_id IS NULL AND status NOT IN ('PAID', 'APPROVED');

UPDATE supplier_invoice SET
    grand_total = amount, grand_total_inr = amount, taxable_value = amount, taxable_value_inr = amount,
    balance_due = CASE WHEN status = 'PAID' THEN 0 ELSE amount END,
    balance_due_inr = CASE WHEN status = 'PAID' THEN 0 ELSE amount END,
    amount_paid = CASE WHEN status = 'PAID' THEN amount ELSE 0 END,
    booking_id = booking_ref, invoice_date = created_date::date, received_on = created_date::date
WHERE vendor_id IS NULL;

ALTER TABLE supplier_invoice DROP COLUMN amount;
ALTER TABLE supplier_invoice DROP COLUMN booking_ref;

CREATE TABLE supplier_invoice_line_item (
    id                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    supplier_invoice_id  VARCHAR(36)  NOT NULL,
    sort_order           INTEGER      NOT NULL DEFAULT 0,
    description          VARCHAR(500) NOT NULL,
    sac_code             VARCHAR(10),
    service_type         VARCHAR(30),
    quantity             NUMERIC(12,3) NOT NULL DEFAULT 1,
    unit_price           NUMERIC(19,2) NOT NULL DEFAULT 0,
    line_subtotal         NUMERIC(19,2) NOT NULL DEFAULT 0,
    discount_amount       NUMERIC(19,2) NOT NULL DEFAULT 0,
    taxable_value         NUMERIC(19,2) NOT NULL DEFAULT 0,
    gst_rate_percent       NUMERIC(6,3)  NOT NULL DEFAULT 0,
    cgst_amount            NUMERIC(19,2) NOT NULL DEFAULT 0,
    sgst_amount            NUMERIC(19,2) NOT NULL DEFAULT 0,
    igst_amount            NUMERIC(19,2) NOT NULL DEFAULT 0,
    line_total             NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at              TIMESTAMP
);
CREATE INDEX idx_sup_inv_line ON supplier_invoice_line_item (supplier_invoice_id, sort_order);

CREATE INDEX idx_sup_inv_vendor  ON supplier_invoice (vendor_id, invoice_date DESC);
CREATE INDEX idx_sup_inv_booking ON supplier_invoice (booking_id);
CREATE INDEX idx_sup_inv_due     ON supplier_invoice (due_date) WHERE status IN ('APPROVED','PARTIALLY_PAID');
-- The same supplier bill can never be entered twice for the same vendor.
CREATE UNIQUE INDEX uq_sup_inv_number ON supplier_invoice (vendor_id, LOWER(supplier_invoice_number))
    WHERE vendor_id IS NOT NULL AND supplier_invoice_number IS NOT NULL AND status <> 'CANCELLED';
