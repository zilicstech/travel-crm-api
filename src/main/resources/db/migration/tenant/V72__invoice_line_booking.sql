-- An invoice may bill several bookings of one client: each line says which booking (and which
-- voucher) it bills. invoice.booking_id stays as the invoice's first booking. A booking may now sit
-- on another booking's draft, so "one draft per booking" is checked by the service over the lines
-- (under the booking row lock), not by an index on invoice.booking_id.
ALTER TABLE invoice_line_item ADD COLUMN booking_id VARCHAR(36);
ALTER TABLE invoice_line_item ADD COLUMN booking_document_id VARCHAR(36);

UPDATE invoice_line_item l SET booking_id = i.booking_id
FROM invoice i WHERE i.id = l.invoice_id AND i.booking_id IS NOT NULL;

CREATE INDEX idx_invoice_line_booking ON invoice_line_item (booking_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_invoice_line_voucher ON invoice_line_item (booking_document_id) WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS idx_invoice_booking_draft;
