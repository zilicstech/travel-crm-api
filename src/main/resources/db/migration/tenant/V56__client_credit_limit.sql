-- The credit an agency extends to a client. NULL = no limit (every client before this release).
-- Used and available are never stored: they are worked out from the ledger and the bookings.
ALTER TABLE client ADD COLUMN IF NOT EXISTS credit_limit NUMERIC(14,2);
