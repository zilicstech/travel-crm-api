-- Same confirm key with a different payload must be refused (spec 7.3); the hash covers passengers + contact.
-- platform_receivable.reason also gains TDS_SHORTFALL (VARCHAR(24), no CHECK constraint, so no DDL needed).
ALTER TABLE platform_booking ADD COLUMN confirm_payload_hash VARCHAR(64);
