-- Principal (tax the full package) vs Disclosed Commission Agent (tax only the service fee,
-- pass supplier net costs through as a non-taxable disbursement) - FRD US-ACC-2.1. Null means
-- "no opinion yet"; InvoiceDocumentService treats null exactly as PRINCIPAL, so every existing
-- invoice's behaviour is unchanged.

ALTER TABLE invoice ADD COLUMN billing_model VARCHAR(20);
