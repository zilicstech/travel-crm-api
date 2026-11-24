-- Visa configuration (2026-11-24, owner rulings). Expand only.
-- A traveller's PAN (10 characters, capitals), shown in the visa requirement data.
ALTER TABLE member ADD COLUMN pan_number varchar(10);
