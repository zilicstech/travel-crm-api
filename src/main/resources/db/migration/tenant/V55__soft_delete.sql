-- Nothing a user deletes is removed any more: the row gets deleted_at and every JPA read hides it
-- (entity @SQLDelete / @SQLRestriction). Unique indexes that a deleted row would still occupy are
-- narrowed to live rows.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['booking', 'booking_document', 'booking_passenger', 'booking_sector',
            'booking_cost_component', 'invoice', 'invoice_line_item', 'invoice_tax', 'supplier_invoice',
            'supplier_invoice_line_item', 'lead_proposal', 'lead_service', 'lead_follow_up',
            'communication_log', 'member_documents', 'agency_setting'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP', t);
    END LOOP;
END $$;

DROP INDEX IF EXISTS uq_sup_inv_number;
CREATE UNIQUE INDEX uq_sup_inv_number ON supplier_invoice (vendor_id, LOWER(supplier_invoice_number))
    WHERE vendor_id IS NOT NULL AND supplier_invoice_number IS NOT NULL AND status <> 'CANCELLED'
      AND deleted_at IS NULL;

DROP INDEX IF EXISTS idx_invoice_booking_draft;
CREATE UNIQUE INDEX idx_invoice_booking_draft ON invoice (booking_id)
    WHERE booking_id IS NOT NULL AND document_type = 'TAX_INVOICE' AND status = 'DRAFT'
      AND deleted_at IS NULL;
