-- An invoice raised in the agency's own software and uploaded on the booking: the file lives at
-- the existing (unused) pdf_file_key; these keep its original name and type for viewing.
ALTER TABLE invoice ADD COLUMN uploaded_file_name VARCHAR(255);
ALTER TABLE invoice ADD COLUMN uploaded_content_type VARCHAR(150);
