-- Pricing rules (owner ruling 2026-10-27, docs/superpowers/specs/2026-10-27-pricing-rules-design.md): shared, named
-- discount and commission rules per product replace per-agency rates. agency_pricing is kept as history only.
CREATE TABLE pricing_rule (
    id                 VARCHAR(36)  PRIMARY KEY,
    kind               VARCHAR(10)  NOT NULL CHECK (kind IN ('DISCOUNT', 'COMMISSION')),
    product            VARCHAR(10)  NOT NULL CHECK (product IN ('FLIGHT', 'HOTEL')),
    name               VARCHAR(60)  NOT NULL,
    pct                NUMERIC(5,2) NOT NULL CHECK (pct >= 0 AND pct <= 100),
    allow_above_earned BOOLEAN      NOT NULL DEFAULT false CHECK (kind = 'COMMISSION' OR NOT allow_above_earned),
    is_default         BOOLEAN      NOT NULL DEFAULT false,
    version            INT          NOT NULL DEFAULT 1,
    created_by         VARCHAR(36)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         VARCHAR(36),
    updated_by_name    VARCHAR(150),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_pricing_rule_name ON pricing_rule (kind, product, lower(name));
CREATE UNIQUE INDEX uq_pricing_rule_default ON pricing_rule (kind, product) WHERE is_default;

-- No row = the Default rules. The foreign keys also stop a rule in use from being deleted.
CREATE TABLE agency_pricing_assignment (
    tenant_id          VARCHAR(36) NOT NULL,
    product            VARCHAR(10) NOT NULL CHECK (product IN ('FLIGHT', 'HOTEL')),
    discount_rule_id   VARCHAR(36) NOT NULL REFERENCES pricing_rule (id),
    commission_rule_id VARCHAR(36) NOT NULL REFERENCES pricing_rule (id),
    updated_by         VARCHAR(36),
    updated_by_name    VARCHAR(150),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product)
);
CREATE INDEX idx_agency_pricing_assignment_discount ON agency_pricing_assignment (discount_rule_id);
CREATE INDEX idx_agency_pricing_assignment_commission ON agency_pricing_assignment (commission_rule_id);

INSERT INTO pricing_rule (id, kind, product, name, pct, is_default, created_by, updated_by_name)
SELECT gen_random_uuid()::text, k, p, 'Default', 0, true, 'system', 'System'
FROM (VALUES ('DISCOUNT'), ('COMMISSION')) kinds (k), (VALUES ('FLIGHT'), ('HOTEL')) products (p);

-- Each agency's rate in force now (the same choice AgencyPricingService.resolve made) becomes rules, one per distinct value,
-- so no agency's price changes. Scheduled (future) and pending rows are not carried over.
CREATE TEMP TABLE inforce ON COMMIT DROP AS
SELECT DISTINCT ON (tenant_id, product) tenant_id, product, discount_pct, commission_pct, allow_commission_above_earned AS above
FROM agency_pricing
WHERE withdrawn_at IS NULL AND approved_at IS NOT NULL AND approved_at <= now() AND effective_from <= now()
ORDER BY tenant_id, product, effective_from DESC;

INSERT INTO pricing_rule (id, kind, product, name, pct, created_by, updated_by_name)
SELECT gen_random_uuid()::text, 'DISCOUNT', product, 'Discount ' || COALESCE(NULLIF(rtrim(rtrim(discount_pct::text, '0'), '.'), ''), '0') || '%', discount_pct, 'system', 'System'
FROM (SELECT DISTINCT product, discount_pct FROM inforce WHERE discount_pct <> 0) d;

INSERT INTO pricing_rule (id, kind, product, name, pct, allow_above_earned, created_by, updated_by_name)
SELECT gen_random_uuid()::text, 'COMMISSION', product,
       'Commission ' || COALESCE(NULLIF(rtrim(rtrim(commission_pct::text, '0'), '.'), ''), '0') || '%' || CASE WHEN above THEN ' (above earned)' ELSE '' END,
       commission_pct, above, 'system', 'System'
FROM (SELECT DISTINCT product, commission_pct, above FROM inforce WHERE commission_pct <> 0 OR above) c;

INSERT INTO agency_pricing_assignment (tenant_id, product, discount_rule_id, commission_rule_id, updated_by_name)
SELECT i.tenant_id, i.product, d.id, c.id, 'System'
FROM inforce i
JOIN pricing_rule d ON d.kind = 'DISCOUNT' AND d.product = i.product
     AND (CASE WHEN i.discount_pct = 0 THEN d.is_default ELSE NOT d.is_default AND d.pct = i.discount_pct END)
JOIN pricing_rule c ON c.kind = 'COMMISSION' AND c.product = i.product
     AND (CASE WHEN i.commission_pct = 0 AND NOT i.above THEN c.is_default
               ELSE NOT c.is_default AND c.pct = i.commission_pct AND c.allow_above_earned = i.above END);
