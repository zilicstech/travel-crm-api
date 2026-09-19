-- ---------------------------------------------------------------------------
-- TAX_RATE_CONFIG - admin-configurable GST/TCS slabs. A rate is never updated
-- in place: a change closes the old row (effective_to) and inserts a new one,
-- so a historical invoice can always be reproduced from the rate that was
-- actually in force when it was issued (see TaxEngine).
-- ---------------------------------------------------------------------------
CREATE TABLE tax_rate_config (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    tax_kind         VARCHAR(20)  NOT NULL,
    label            VARCHAR(150) NOT NULL,
    sac_code         VARCHAR(10),
    supply_nature    VARCHAR(30)  NOT NULL,
    rate_percent     NUMERIC(6,3) NOT NULL DEFAULT 0,
    taxable_percent  NUMERIC(6,3) NOT NULL DEFAULT 100.000,
    threshold_amount NUMERIC(19,2),
    tcs_section      VARCHAR(20),
    effective_from   DATE         NOT NULL,
    effective_to     DATE,
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP, created_by VARCHAR(36),
    updated_at TIMESTAMP, updated_by VARCHAR(36)
);

CREATE INDEX idx_tax_rate_config_lookup ON tax_rate_config (tax_kind, supply_nature, effective_from DESC);

-- One live default per (kind, supply nature) - the row TaxEngine falls back to when no
-- specific override is chosen.
CREATE UNIQUE INDEX idx_tax_rate_default ON tax_rate_config (tax_kind, supply_nature)
    WHERE is_default AND is_active;

-- Sensible starting slabs so a fresh tenant can preview tax immediately. An owner can adjust,
-- deactivate, or version these from Settings; this migration never runs again after the row
-- exists, so it never overwrites an owner's own edit.
INSERT INTO tax_rate_config (id, tax_kind, label, sac_code, supply_nature, rate_percent, taxable_percent, effective_from, is_default, is_active, created_at)
VALUES
    (gen_random_uuid()::text, 'GST', 'Domestic tour package (abated)', '9985', 'DOMESTIC_PACKAGE', 5.000, 100.000, CURRENT_DATE, TRUE, TRUE, now()),
    (gen_random_uuid()::text, 'GST', 'Air ticket booking / service fee', '9985', 'AIR_TICKET',      18.000, 100.000, CURRENT_DATE, TRUE, TRUE, now()),
    (gen_random_uuid()::text, 'GST', 'Hotel booking service fee',        '9985', 'HOTEL_ONLY',       18.000, 100.000, CURRENT_DATE, TRUE, TRUE, now()),
    (gen_random_uuid()::text, 'GST', 'Visa service fee',                 '9985', 'VISA_SERVICE',     18.000, 100.000, CURRENT_DATE, TRUE, TRUE, now()),
    (gen_random_uuid()::text, 'GST', 'Other services',                   '9985', 'OTHER',            18.000, 100.000, CURRENT_DATE, TRUE, TRUE, now());

INSERT INTO tax_rate_config (id, tax_kind, label, supply_nature, rate_percent, threshold_amount, tcs_section, effective_from, is_default, is_active, created_at)
VALUES
    (gen_random_uuid()::text, 'TCS', 'Overseas tour package', 'OVERSEAS_PACKAGE', 5.000, 700000.00, '206C(1G)', CURRENT_DATE, TRUE, TRUE, now());

-- ---------------------------------------------------------------------------
-- CLIENT - GST identity fields the accounting module needs to determine
-- place of supply and to print a compliant tax invoice.
-- ---------------------------------------------------------------------------
ALTER TABLE client ADD COLUMN gstin           VARCHAR(20);
ALTER TABLE client ADD COLUMN state_code      VARCHAR(2);
ALTER TABLE client ADD COLUMN billing_address VARCHAR(500);
ALTER TABLE client ADD COLUMN is_overseas     BOOLEAN NOT NULL DEFAULT FALSE;
