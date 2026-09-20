-- ---------------------------------------------------------------------------
-- Accounts-payable profile on vendor - payment terms, credit limit, banking.
-- state_code is load-bearing, not decoration: it decides whether a supplier's
-- GST on a recorded bill should be CGST+SGST (intra-state) or IGST
-- (inter-state) - see SupplierInvoiceService's recorded-tax validation.
-- ---------------------------------------------------------------------------
ALTER TABLE vendor ADD COLUMN state_code                VARCHAR(2);
ALTER TABLE vendor ADD COLUMN pan_number                VARCHAR(10);
ALTER TABLE vendor ADD COLUMN is_prepaid                BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE vendor ADD COLUMN payment_terms_days        INTEGER;          -- NULL = due on receipt
ALTER TABLE vendor ADD COLUMN credit_limit_inr          NUMERIC(19,2);
ALTER TABLE vendor ADD COLUMN low_balance_threshold_inr NUMERIC(19,2);
ALTER TABLE vendor ADD COLUMN tds_section               VARCHAR(20);
ALTER TABLE vendor ADD COLUMN tds_rate_percent          NUMERIC(6,3);
ALTER TABLE vendor ADD COLUMN bank_account_name         VARCHAR(150);
ALTER TABLE vendor ADD COLUMN bank_account_number       VARCHAR(34);
ALTER TABLE vendor ADD COLUMN bank_ifsc                 VARCHAR(11);
