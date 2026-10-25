-- DPDP: passenger data of a finished platform booking is removed after the retention period
-- (platform setting pax_retention_days); money, supplier ids and statuses stay. NULL = not purged yet.
ALTER TABLE platform_booking ADD COLUMN pax_purged_at TIMESTAMPTZ;

INSERT INTO platform_setting (key, value) VALUES ('pax_retention_days', '90') ON CONFLICT (key) DO NOTHING;
