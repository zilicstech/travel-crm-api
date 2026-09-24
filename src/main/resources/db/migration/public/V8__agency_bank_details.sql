-- Printed on every customer invoice's bank block - see ACCOUNTING_REDESIGN_SPEC.md gap 5
-- and §3 (the client's own invoice carries account number, IFSC and branch). All nullable:
-- an agency that hasn't filled these in yet just gets no bank block on its PDFs.
ALTER TABLE tenant ADD COLUMN bank_account_name   VARCHAR(200);
ALTER TABLE tenant ADD COLUMN bank_account_number VARCHAR(40);
ALTER TABLE tenant ADD COLUMN bank_ifsc_code      VARCHAR(20);
ALTER TABLE tenant ADD COLUMN bank_branch         VARCHAR(200);
