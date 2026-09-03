-- Needed to serve a stored document back with the right Content-Type header; the upload
-- endpoint already receives it from the multipart request and simply wasn't persisting it.
ALTER TABLE member_documents ADD COLUMN content_type VARCHAR(150);
