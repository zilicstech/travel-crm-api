-- Visa configuration (2026-11-24, owner rulings). Expand only.
-- A traveller's PAN (10 characters, capitals), shown in the visa requirement data.
ALTER TABLE member ADD COLUMN pan_number varchar(10);

-- The fields an application asks for: COMMON (every country) or COUNTRY (one country, spelled as the country picker spells it).
CREATE TABLE visa_field (
    id varchar(36) PRIMARY KEY,
    scope varchar(10) NOT NULL CHECK (scope IN ('COMMON', 'COUNTRY')),
    country varchar(100),
    label varchar(60) NOT NULL,
    type varchar(10) NOT NULL CHECK (type IN ('TEXT', 'DROPDOWN', 'DATE', 'YES_NO', 'FILE')),
    options text[],
    mandatory boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamp NOT NULL,
    created_by varchar(36),
    updated_at timestamp,
    updated_by varchar(36),
    CHECK ((scope = 'COMMON') = (country IS NULL))
);
-- One active field per label in a scope and country; a removed one may be added back (restored).
CREATE UNIQUE INDEX ux_visa_field_label ON visa_field (scope, lower(coalesce(country, '')), lower(label)) WHERE active;

-- One application is one traveller; values of the configured fields are a map field id -> text.
ALTER TABLE visa ADD COLUMN member_id varchar(36);
ALTER TABLE visa ADD COLUMN member_name varchar(150);
ALTER TABLE visa ADD COLUMN passport_expiry date;
ALTER TABLE visa ADD COLUMN nationality varchar(60);
ALTER TABLE visa ADD COLUMN field_values jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE visa ADD COLUMN purchase_price numeric(12, 2);
ALTER TABLE visa ADD COLUMN purchase_currency varchar(3);
ALTER TABLE visa ADD COLUMN selling_price numeric(12, 2);
ALTER TABLE visa ADD COLUMN vendor_id varchar(36);
ALTER TABLE visa ADD COLUMN vendor_name varchar(150);
ALTER TABLE visa ADD COLUMN remarks varchar(500);
ALTER TABLE visa ADD COLUMN version bigint NOT NULL DEFAULT 0;
-- The sheet's S.NO: existing cases numbered by when they were opened.
CREATE SEQUENCE visa_case_no_seq;
ALTER TABLE visa ADD COLUMN case_no bigint;
UPDATE visa v SET case_no = n.rn FROM (
    SELECT id, row_number() OVER (ORDER BY created_date NULLS FIRST, id) AS rn FROM visa) n WHERE v.id = n.id;
SELECT setval('visa_case_no_seq', coalesce((SELECT max(case_no) FROM visa), 0) + 1, false);
ALTER TABLE visa ALTER COLUMN case_no SET DEFAULT nextval('visa_case_no_seq');
ALTER TABLE visa ALTER COLUMN case_no SET NOT NULL;
ALTER SEQUENCE visa_case_no_seq OWNED BY visa.case_no;
CREATE UNIQUE INDEX ux_visa_case_no ON visa (case_no);
CREATE INDEX idx_visa_field_values ON visa USING gin (field_values jsonb_path_ops);
CREATE INDEX idx_visa_country_created ON visa (lower(country), created_date);

-- One file per File field of an application; a new upload replaces it.
CREATE TABLE visa_file (
    id varchar(36) PRIMARY KEY,
    visa_id varchar(36) NOT NULL REFERENCES visa (id),
    field_id varchar(36) NOT NULL REFERENCES visa_field (id),
    file_key varchar(500) NOT NULL,
    file_name varchar(255) NOT NULL,
    file_type varchar(100),
    uploaded_by varchar(36),
    uploaded_by_name varchar(150),
    uploaded_at timestamp NOT NULL,
    UNIQUE (visa_id, field_id)
);
