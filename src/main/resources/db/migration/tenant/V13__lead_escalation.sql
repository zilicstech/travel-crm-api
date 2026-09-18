-- No escalation concept exists anywhere today (zero hits for "escalat" in the codebase before
-- this). In-app surfacing only, per scope: a flag + reason an owner or senior agent sets, read
-- back by GET /api/escalations alongside overdue follow-ups and past-deadline bookings. No
-- scheduler, no push, no email.
ALTER TABLE lead ADD COLUMN escalated        BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lead ADD COLUMN escalated_at     TIMESTAMP;
ALTER TABLE lead ADD COLUMN escalation_reason VARCHAR(255);

CREATE INDEX idx_lead_escalated ON lead (escalated) WHERE escalated;
