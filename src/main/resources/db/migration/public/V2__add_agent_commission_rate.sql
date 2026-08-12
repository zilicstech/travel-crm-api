-- Commission % of booking profit attributed to the agent. Turns the UI's previously
-- hardcoded mock "commission" figure into a real, owner-configurable, computed value:
-- commission = SUM(booking.profit WHERE agent_id = X) * commission_rate / 100.
ALTER TABLE agent ADD COLUMN commission_rate NUMERIC(5, 2) NOT NULL DEFAULT 5.00;
