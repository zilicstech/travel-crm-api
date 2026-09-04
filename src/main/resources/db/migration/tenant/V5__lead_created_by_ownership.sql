-- A lead is no longer "assigned to" a single owning agent. Ownership becomes whoever
-- created it (created_by, already present, already set on every insert) - the creator sees
-- and edits their own lead, the Owner sees and edits every lead. Assignment now happens at
-- the service level instead (see lead_service.assigned_agent_id), not the lead level.
DROP INDEX IF EXISTS idx_lead_assigned_to;
ALTER TABLE lead DROP COLUMN assigned_to;
ALTER TABLE lead DROP COLUMN assigned_agent_name;
ALTER TABLE lead ADD COLUMN created_by_name VARCHAR(150);
CREATE INDEX idx_lead_created_by ON lead (created_by);
