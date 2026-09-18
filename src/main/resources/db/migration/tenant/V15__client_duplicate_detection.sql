-- Today's duplicate check is one exact case-insensitive identifier lookup at create time
-- (idx_client_identifier, ClientService.createClient). This adds a pre-save advisory check
-- that also catches a phone written differently ("+91 98765 43210" vs "9876543210") and a
-- name that is merely similar, not identical - see ClientService.checkDuplicates.
--
-- pg_trgm is core-contrib, ships with every standard Postgres install; verified against this
-- database before writing this migration.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_client_name_trgm ON client USING GIN (name gin_trgm_ops);

-- Set only when a client is created despite the duplicate-check warning, so a record flagged
-- once stays flagged rather than relying on the agent remembering. Nullable, no FK (matches
-- the flat-column style everywhere else) - it is advisory, not a merge.
ALTER TABLE client ADD COLUMN possible_duplicate_of VARCHAR(36);
