-- CRM improvements from the client's document (owner rulings 2026-11-20). Expand only: every column is nullable.
-- A Miscellaneous service describes itself in misc_details; a Visa service carries its application status
-- (NOT_APPLIED, APPLIED, PENDING, CONFIRMED, REJECTED; null reads as NOT_APPLIED).
ALTER TABLE lead_service ADD COLUMN misc_details varchar(500);
ALTER TABLE lead_service ADD COLUMN visa_status varchar(20);

-- The visa's duration and type on a Visa proposal line, e.g. "30 days" or "Extension of existing visa".
ALTER TABLE lead_proposal ADD COLUMN visa_duration varchar(60);

-- Recorded by hand after issue; never read by money, accounting or access rules.
ALTER TABLE booking ADD COLUMN assigned_by_id varchar(36);
ALTER TABLE booking ADD COLUMN assigned_by_name varchar(150);
ALTER TABLE booking ADD COLUMN issued_by_id varchar(36);
ALTER TABLE booking ADD COLUMN issued_by_name varchar(150);
ALTER TABLE booking ADD COLUMN actioned_by_id varchar(36);
ALTER TABLE booking ADD COLUMN actioned_by_name varchar(150);
ALTER TABLE booking ADD COLUMN purchase_currency varchar(3);
ALTER TABLE booking ADD COLUMN selling_currency varchar(3);
