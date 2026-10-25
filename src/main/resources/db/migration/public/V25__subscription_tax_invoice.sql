-- Subscription invoices become GST tax invoices (B2B slice 5, Task 4 review).
-- The tax split, SAC and both parties' details are frozen at issue so the PDF and the API always agree.
ALTER TABLE subscription_invoice ADD COLUMN cgst NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE subscription_invoice ADD COLUMN sgst NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE subscription_invoice ADD COLUMN igst NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE subscription_invoice ADD COLUMN sac_code VARCHAR(8);
ALTER TABLE subscription_invoice ADD COLUMN billing_details JSONB NOT NULL DEFAULT '{}';
-- Invoices issued before this migration charged IGST.
UPDATE subscription_invoice SET igst = gst WHERE cgst = 0 AND sgst = 0 AND igst = 0;

-- Voyra's legal identity (empty until the owner fills it in; the app warns at startup while the GSTIN is empty).
INSERT INTO platform_setting (key, value) VALUES
  ('voyra_legal_name', ''),
  ('voyra_gstin', ''),
  ('voyra_address', ''),
  ('voyra_state_code', ''),
  ('subscription_sac', '998314')
ON CONFLICT (key) DO NOTHING;
