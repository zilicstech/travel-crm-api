-- tenant liveness: single truth is status; is_active stays as a derived mirror for existing readers
ALTER TABLE tenant ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';
UPDATE tenant SET status = CASE WHEN is_active THEN 'ACTIVE' ELSE 'DEACTIVATED' END;
ALTER TABLE tenant ADD CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED'));
ALTER TABLE tenant ADD COLUMN pan VARCHAR(10), ADD COLUMN cin VARCHAR(30), ADD COLUMN trade_name VARCHAR(200),
    ADD COLUMN approved_at TIMESTAMPTZ, ADD COLUMN approved_by VARCHAR(36), ADD COLUMN booking_blocked BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE agency_application (
    id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(14) NOT NULL,            -- DRAFT|SUBMITTED|UNDER_REVIEW|MORE_INFO|APPROVED|REJECTED
    agency_name VARCHAR(150) NOT NULL, legal_name VARCHAR(200), trade_name VARCHAR(200),
    pan VARCHAR(10) NOT NULL, gstin VARCHAR(15), cin VARCHAR(30),
    address VARCHAR(500), city VARCHAR(100), state_code VARCHAR(2), pincode VARCHAR(10),
    owner_name VARCHAR(150) NOT NULL, owner_email VARCHAR(150) NOT NULL, owner_phone VARCHAR(30) NOT NULL,
    expected_monthly_volume VARCHAR(40), base_currency_code VARCHAR(3) NOT NULL DEFAULT 'INR',
    consent_at TIMESTAMPTZ, upload_token_hash VARCHAR(64) NOT NULL, upload_token_expires_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ, reviewed_by VARCHAR(36), reviewed_at TIMESTAMPTZ, review_note VARCHAR(2000),
    rejection_reason VARCHAR(2000), tenant_id VARCHAR(36), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agency_application_status_created ON agency_application (status, created_at DESC);
CREATE INDEX idx_agency_application_owner_email ON agency_application (LOWER(owner_email));

CREATE TABLE kyc_document (
    id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL,
    type VARCHAR(20) NOT NULL,              -- PAN|GST|BUSINESS_PROOF|OWNER_ID|CANCELLED_CHEQUE|ADDRESS_PROOF|IATA|OTHER
    file_key VARCHAR(500) NOT NULL, file_name VARCHAR(255) NOT NULL, content_type VARCHAR(100), size_bytes BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',   -- PENDING|VERIFIED|REJECTED
    note VARCHAR(1000), reviewed_by VARCHAR(36), reviewed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kyc_document_application ON kyc_document (application_id);

CREATE TABLE agency_pricing (
    id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, product VARCHAR(10) NOT NULL,   -- FLIGHT|HOTEL
    discount_pct NUMERIC(5,2) NOT NULL, commission_pct NUMERIC(5,2) NOT NULL,
    allow_commission_above_earned BOOLEAN NOT NULL DEFAULT false,
    effective_from TIMESTAMPTZ NOT NULL, worst_case_net_per_10k NUMERIC(19,2) NOT NULL,
    requires_second_approval BOOLEAN NOT NULL, created_by VARCHAR(36) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by VARCHAR(36), approved_at TIMESTAMPTZ,
    CONSTRAINT uq_agency_pricing UNIQUE (tenant_id, product, effective_from)
);

CREATE TABLE platform_supplier_credential (
    id VARCHAR(36) PRIMARY KEY, provider VARCHAR(30) NOT NULL, environment VARCHAR(10) NOT NULL,
    base_url VARCHAR(255) NOT NULL, api_key_encrypted VARCHAR(1024) NOT NULL, user_id_encrypted VARCHAR(1024),
    is_active BOOLEAN NOT NULL DEFAULT false, updated_by VARCHAR(36), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_platform_supplier_credential_active ON platform_supplier_credential (provider) WHERE is_active;

CREATE TABLE platform_setting (key VARCHAR(60) PRIMARY KEY, value VARCHAR(4000) NOT NULL, updated_by VARCHAR(36), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO platform_setting (key, value) VALUES
  ('discount_base', 'GROSS'),
  ('tds_rate', '0'),
  ('commission_estimate_pct', '5'),
  ('reference_fare_basket', '[{"label":"Domestic LCC","gross":5000,"base":3500},{"label":"Domestic FSC","gross":9000,"base":6000},{"label":"International","gross":45000,"base":22000}]');

CREATE TABLE platform_audit_log (
    id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, actor_name VARCHAR(150) NOT NULL,
    action VARCHAR(30) NOT NULL, entity_type VARCHAR(30) NOT NULL, entity_id VARCHAR(36) NOT NULL,
    detail JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_audit_entity ON platform_audit_log (entity_type, entity_id, created_at DESC);
