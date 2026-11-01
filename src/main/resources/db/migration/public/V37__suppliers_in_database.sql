-- V37 (expand only, spec 2026-11-01 Part 1): suppliers in the database. V39, next release, drops what this stops reading.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform_setting WHERE key = 'discount_base' AND value <> 'GROSS') THEN
        RAISE EXCEPTION 'V37: discount_base is not GROSS; set it to GROSS and check agency prices, then deploy again';
    END IF;
    IF EXISTS (SELECT 1 FROM pricing_rule r
               WHERE r.kind = 'COMMISSION' AND NOT r.allow_above_earned
                 AND r.pct > COALESCE((SELECT p.commission_estimate_pct FROM platform_supplier_product p
                                       JOIN platform_supplier_credential c ON c.id = p.credential_id
                                       WHERE c.provider = 'TRIPJACK' AND c.is_active AND p.product = r.product), 5)) THEN
        RAISE EXCEPTION 'V37: a commission rule without allow-above-earned exceeds the supplier estimate; lower it or allow it, then deploy again';
    END IF;
END $$;

ALTER TABLE platform_supplier_credential DROP CONSTRAINT uq_platform_supplier_credential_provider;
DROP INDEX uq_platform_supplier_credential_active;
CREATE UNIQUE INDEX uq_platform_supplier_credential_name ON platform_supplier_credential (lower(name));

ALTER TABLE platform_supplier_product
    ADD COLUMN search_enabled  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN booking_enabled BOOLEAN NOT NULL DEFAULT false,
    ALTER COLUMN commission_estimate_pct SET DEFAULT 5; -- no longer written; old instances still read it

-- The mock is a supplier row. Its key is blank (SQL cannot encrypt with the app key); every toggle off.
INSERT INTO platform_supplier_credential (id, name, provider, environment, base_url, api_key_encrypted, is_active, created_at, updated_at)
SELECT gen_random_uuid()::text, 'Mock supplier', 'MOCK', 'MOCK', 'mock://local', '', false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM platform_supplier_credential WHERE lower(name) = 'mock supplier');
INSERT INTO platform_supplier_product (credential_id, product)
SELECT c.id, p.product FROM platform_supplier_credential c, (VALUES ('FLIGHT'), ('HOTEL')) p (product)
WHERE c.provider = 'MOCK' AND lower(c.name) = 'mock supplier'
ON CONFLICT DO NOTHING;
UPDATE platform_booking SET credential_id = (SELECT id FROM platform_supplier_credential WHERE provider = 'MOCK' AND lower(name) = 'mock supplier')
WHERE environment = 'MOCK' AND credential_id IS NULL;
