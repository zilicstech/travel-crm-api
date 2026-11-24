-- A Miscellaneous service or booking names its sub-category (railway ticket, insurance, SIM card) as plain text,
-- picked from the agency's MISC_CATEGORY settings; text, not an id, so a renamed or removed option never rewrites it.
ALTER TABLE lead_service ADD COLUMN misc_category varchar(150);
ALTER TABLE booking ADD COLUMN misc_category varchar(150);
