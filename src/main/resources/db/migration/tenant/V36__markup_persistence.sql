-- Markup ("cost + agency margin = selling price") was a client-side calculator only, thrown
-- away the moment sellingPrice was computed - nothing on the proposal line or the booking
-- recorded what markup was actually intended. Persist it on both, and give the agency a
-- default per service type so an agent isn't guessing company policy on every quote.

ALTER TABLE lead_proposal ADD COLUMN markup_mode VARCHAR(10);
ALTER TABLE lead_proposal ADD COLUMN markup_value NUMERIC(19,4);

ALTER TABLE booking ADD COLUMN markup_mode VARCHAR(10);
ALTER TABLE booking ADD COLUMN markup_value NUMERIC(19,4);

CREATE TABLE markup_default (
    service_type VARCHAR(20) NOT NULL PRIMARY KEY,
    mode VARCHAR(10) NOT NULL,
    value NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP,
    updated_by VARCHAR(36)
);
