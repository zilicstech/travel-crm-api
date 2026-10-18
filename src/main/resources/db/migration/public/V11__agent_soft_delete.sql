-- A removed agent or accountant is marked, not deleted; the email is freed for a new login.
ALTER TABLE agent ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
DROP INDEX IF EXISTS idx_agent_email;
CREATE UNIQUE INDEX idx_agent_email ON agent (LOWER(email)) WHERE deleted_at IS NULL;
