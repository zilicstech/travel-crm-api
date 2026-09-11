-- file_key has existed since V2 but was never written; serving a stored voucher back
-- with the right filename and Content-Type needs these two, exactly as V4 added them
-- to member_documents for the same reason.
ALTER TABLE lead_voucher ADD COLUMN file_name    VARCHAR(255);
ALTER TABLE lead_voucher ADD COLUMN content_type VARCHAR(150);
