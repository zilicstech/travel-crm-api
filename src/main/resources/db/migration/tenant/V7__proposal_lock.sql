-- Once a customer confirms their option picks on the public proposal, the whole proposal
-- freezes (both the customer's own selection endpoint and the agent's own line edits)
-- until an agent explicitly unlocks it — protects against silent divergence from what the
-- customer actually saw and confirmed.
ALTER TABLE lead ADD COLUMN proposal_locked BOOLEAN NOT NULL DEFAULT false;
