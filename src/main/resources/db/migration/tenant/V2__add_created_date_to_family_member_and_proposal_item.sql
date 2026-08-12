-- Blueprint §8.4: every table carries created_date. These two were the only tenant
-- tables missing it. Additive and nullable, so existing rows are untouched.
ALTER TABLE family_member ADD COLUMN created_date TIMESTAMP;
ALTER TABLE proposal_item ADD COLUMN created_date TIMESTAMP;
