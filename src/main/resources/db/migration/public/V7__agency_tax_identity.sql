-- tenant.gst_number and tenant.currency already exist. currency is free text ("₹ (INR)") and
-- stays a display-only label; base_currency_code is the machine field the accounting module
-- reads. state_code is the agency's own GST home state, needed to decide CGST+SGST vs IGST
-- (see TaxEngine).
ALTER TABLE tenant ADD COLUMN state_code         VARCHAR(2);
ALTER TABLE tenant ADD COLUMN base_currency_code VARCHAR(3) NOT NULL DEFAULT 'INR';
ALTER TABLE tenant ADD COLUMN default_sac_code   VARCHAR(10);
ALTER TABLE tenant ADD COLUMN invoice_terms      VARCHAR(2000);
ALTER TABLE tenant ADD COLUMN legal_name         VARCHAR(200);
