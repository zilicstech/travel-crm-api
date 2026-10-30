-- Suppliers (owner feedback 2026-10-30, docs/superpowers/specs/2026-10-30-platform-feedback-round-design.md Phase C):
-- each platform supplier row gets a name and per-product commission terms, and there is one row per integration.

-- One row per provider from now on. Fail loudly rather than pick a row to delete: an operator keeps one by hand.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform_supplier_credential GROUP BY provider HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V34: platform_supplier_credential has more than one row for a provider; delete the extra rows by hand, then deploy again';
    END IF;
END $$;
ALTER TABLE platform_supplier_credential ADD CONSTRAINT uq_platform_supplier_credential_provider UNIQUE (provider);

ALTER TABLE platform_supplier_credential ADD COLUMN name VARCHAR(80);
UPDATE platform_supplier_credential SET name = 'Tripjack';
ALTER TABLE platform_supplier_credential ALTER COLUMN name SET NOT NULL;

CREATE TABLE platform_supplier_product (
    credential_id           VARCHAR(36)  NOT NULL REFERENCES platform_supplier_credential (id),
    product                 VARCHAR(10)  NOT NULL CHECK (product IN ('FLIGHT', 'HOTEL')),
    commission_estimate_pct NUMERIC(5,2) NOT NULL CHECK (commission_estimate_pct >= 0 AND commission_estimate_pct <= 100),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              VARCHAR(36),
    PRIMARY KEY (credential_id, product)
);

-- Today's single platform figure becomes both products' terms, so no price moves. The commission_estimate_pct setting row
-- is kept (not deleted) so an instance still running the old code prices the same while a rolling deploy finishes.
INSERT INTO platform_supplier_product (credential_id, product, commission_estimate_pct)
SELECT c.id, p.product,
       COALESCE((SELECT value::numeric FROM platform_setting WHERE key = 'commission_estimate_pct'), 5)
FROM platform_supplier_credential c, (VALUES ('FLIGHT'), ('HOTEL')) p (product);
