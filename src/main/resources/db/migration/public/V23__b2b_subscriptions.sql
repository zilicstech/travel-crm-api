-- CRM SKUs and subscriptions (B2B slice 5). Public schema; no tenant table may be named
-- subscription, subscription_invoice or voyra_invoice_sequence (tenant search_path is "tenant_x, public").
CREATE TABLE subscription (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL UNIQUE,
    plan VARCHAR(8) NOT NULL CHECK (plan IN ('STARTER','CUSTOM','SUITE')),
    skus TEXT[] NOT NULL DEFAULT '{}',   -- Sku names; read for CUSTOM only
    seats INT NOT NULL DEFAULT 0 CHECK (seats >= 0),  -- SUITE: extra on top of the 5 included
    status VARCHAR(10) NOT NULL CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','READ_ONLY','CANCELLED')),
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,              -- also the end of a waiver; NULL = no expiry
    trial_ends_at TIMESTAMPTZ,
    waived_reason VARCHAR(16) CHECK (waived_reason IN ('FOUNDING_AGENCY','COMPLIMENTARY','GMV_THRESHOLD')),
    billing_cycle VARCHAR(8) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_cycle IN ('MONTHLY','ANNUAL')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE subscription_invoice (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    number VARCHAR(30) UNIQUE,           -- VOY-SUB-<yyyy>-<seq>, allocated on issue
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    lines JSONB NOT NULL DEFAULT '[]',
    amount NUMERIC(19,2) NOT NULL,
    gst NUMERIC(19,2) NOT NULL,
    total NUMERIC(19,2) NOT NULL,
    status VARCHAR(6) NOT NULL CHECK (status IN ('DRAFT','ISSUED','PAID','VOID')),
    due_at TIMESTAMPTZ,
    gateway_order_id VARCHAR(60),
    paid_at TIMESTAMPTZ,
    paid_via VARCHAR(10),                -- GATEWAY|BANK
    pdf_key VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscription_invoice_tenant ON subscription_invoice (tenant_id, created_at DESC);
CREATE INDEX idx_subscription_invoice_status_due ON subscription_invoice (status, due_at);

-- Voyra's own invoice series, incremented with UPDATE ... RETURNING so concurrent issues serialise on the row.
CREATE TABLE voyra_invoice_sequence (
    series VARCHAR(20) NOT NULL,         -- e.g. VOY-SUB
    year INT NOT NULL,
    last_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (series, year)
);

INSERT INTO platform_setting (key, value) VALUES
  ('sku_prices', '{"CRM_CORE":2499,"STAFF_SEATS":399,"ACCOUNTS_GST":1999,"REPORTS":699,"VISA":499,"SUITE":4999}'),
  ('founding_agencies', '["Voyra Global"]')
ON CONFLICT (key) DO NOTHING;

-- Every existing agency moves onto Suite: the founding agencies (named in the setting above,
-- matched by agency name) are waived with no expiry; everyone else gets 90 days complimentary.
INSERT INTO subscription (id, tenant_id, plan, skus, seats, status, period_start, period_end, waived_reason)
SELECT gen_random_uuid()::text, t.id, 'SUITE', ARRAY['CRM_CORE','STAFF_SEATS','ACCOUNTS_GST','REPORTS','VISA'], 0, 'ACTIVE', now(),
       CASE WHEN f.founding THEN NULL ELSE now() + interval '90 days' END,
       CASE WHEN f.founding THEN 'FOUNDING_AGENCY' ELSE 'COMPLIMENTARY' END
FROM tenant t
CROSS JOIN LATERAL (
    SELECT EXISTS (
        SELECT 1 FROM platform_setting s, jsonb_array_elements_text(s.value::jsonb) AS n(name)
        WHERE s.key = 'founding_agencies' AND lower(trim(n.name)) = lower(trim(t.agency_name))
    ) AS founding
) f
ON CONFLICT (tenant_id) DO NOTHING;
