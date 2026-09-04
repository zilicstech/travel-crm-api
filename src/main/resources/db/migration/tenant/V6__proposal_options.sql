-- Lets one proposal line be a plain add-on (option_group NULL, always counted) or one of
-- several mutually-exclusive alternatives (shared option_group, exactly one is_selected).
-- Existing rows keep option_group NULL, so pre-existing quotes are unaffected by this migration.
ALTER TABLE lead_proposal ADD COLUMN option_group VARCHAR(36);
ALTER TABLE lead_proposal ADD COLUMN is_selected BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE lead_proposal ADD COLUMN selected_by VARCHAR(20);
ALTER TABLE lead_proposal ADD COLUMN selected_at TIMESTAMP;

CREATE INDEX idx_lead_proposal_option_group ON lead_proposal (lead_id, option_group);
