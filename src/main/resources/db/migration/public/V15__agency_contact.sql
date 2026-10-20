-- The agency's phone, email and website, printed in the invoice header.
ALTER TABLE tenant ADD COLUMN contact_phone VARCHAR(30);
ALTER TABLE tenant ADD COLUMN contact_email VARCHAR(150);
ALTER TABLE tenant ADD COLUMN website VARCHAR(150);
