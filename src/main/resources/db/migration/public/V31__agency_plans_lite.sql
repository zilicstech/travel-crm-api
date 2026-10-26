-- Agency plans (2026-10-27): the CRM Lite plan, priced in sku_prices, and the optional agency logo at onboarding
-- (kyc_document.type has no CHECK, so AGENCY_LOGO needs no change here).
ALTER TABLE subscription DROP CONSTRAINT subscription_plan_check;
ALTER TABLE subscription ADD CONSTRAINT subscription_plan_check CHECK (plan IN ('STARTER','LITE','CUSTOM','SUITE'));

-- Only adds the key: a price an admin already set for LITE is kept.
UPDATE platform_setting SET value = (value::jsonb || '{"LITE":2999}'::jsonb)::text, updated_at = now()
WHERE key = 'sku_prices' AND value::jsonb -> 'LITE' IS NULL;
