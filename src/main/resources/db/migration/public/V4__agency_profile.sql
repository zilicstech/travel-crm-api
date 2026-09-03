-- Agency profile fields the Settings/Account screens need. All nullable/additive - an
-- existing tenant row is a valid agency with none of these filled in yet.
ALTER TABLE tenant ADD COLUMN gst_number VARCHAR(30);
ALTER TABLE tenant ADD COLUMN address VARCHAR(500);
ALTER TABLE tenant ADD COLUMN currency VARCHAR(10);
ALTER TABLE tenant ADD COLUMN default_commission NUMERIC(5, 2);
ALTER TABLE tenant ADD COLUMN logo_key VARCHAR(500);
