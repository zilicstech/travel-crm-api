-- Each voucher carries its own cost, markup and the selling price worked out from them; the
-- booking's net cost and selling price are the running total of its vouchers. A voucher with a
-- supplier drafts its own supplier bill, remembered here so deleting the voucher can withdraw it.
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS net_cost NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS selling_price NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS markup_mode VARCHAR(10);
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS markup_value NUMERIC(14,2);
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS vendor_id VARCHAR(36);
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS supplier_invoice_id VARCHAR(36);
