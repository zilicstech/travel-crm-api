-- Client is no longer mapped to an owning agent. Every agent and the Owner see and can act on
-- every client in the agency; ownership-based access control on Client is removed entirely
-- (it stays unchanged on Booking, Invoice, Visa, and Lead.assignedTo - those are separate
-- concerns and out of scope here).
DROP INDEX IF EXISTS idx_client_agent;
ALTER TABLE client DROP COLUMN agent_id;
ALTER TABLE client DROP COLUMN agent_name;
