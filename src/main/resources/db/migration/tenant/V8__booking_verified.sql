-- Accounts marks a booking verified (checked) without invoicing it; an invoiced booking counts as verified anyway.
ALTER TABLE booking ADD COLUMN verified_at timestamp without time zone;
ALTER TABLE booking ADD COLUMN verified_by character varying(255);
