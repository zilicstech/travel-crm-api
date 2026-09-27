-- A booking can now name the real Vendor it was placed with, not just a free-text supplier
-- name snapshot (booking.supplier, unchanged). This is what lets a confirmed booking
-- auto-draft the matching supplier bill (see SupplierInvoiceService#createAutoDraft).

ALTER TABLE booking ADD COLUMN vendor_id VARCHAR(36);

-- Distinguishes a bill the system drafted from the booking itself (unconfirmed, sitting in
-- the accountant's "awaiting confirmation" queue) from one an accountant typed in by hand.
ALTER TABLE supplier_invoice ADD COLUMN auto_drafted BOOLEAN NOT NULL DEFAULT FALSE;
