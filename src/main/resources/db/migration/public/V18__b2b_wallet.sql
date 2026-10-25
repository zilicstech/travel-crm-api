-- Wallet tables live in the public schema. No tenant table may be named wallet, wallet_entry,
-- topup_request, gateway_order, gateway_webhook_orphan or platform_receivable: the tenant
-- search_path is "tenant_x, public" and a same-named tenant table would shadow these.
CREATE TABLE wallet (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    held NUMERIC(19,2) NOT NULL DEFAULT 0,
    is_test BOOLEAN NOT NULL DEFAULT false,
    low_balance_threshold NUMERIC(19,2),
    low_balance_notified_at TIMESTAMPTZ,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_at TIMESTAMPTZ, closed_by VARCHAR(36), closure_utr VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (held >= 0 AND balance >= held)
);

CREATE TABLE wallet_entry (
    id VARCHAR(36) PRIMARY KEY,
    wallet_id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL,
    type VARCHAR(20) NOT NULL,     -- TOPUP|HOLD|HOLD_RELEASE|BOOKING_DEBIT|COMMISSION_CREDIT|TDS_DEDUCTED|REFUND_CREDIT|COMMISSION_RECALL|RECEIVABLE_SETTLED|ADJUSTMENT|CLOSURE_REFUND
    amount NUMERIC(19,2) NOT NULL, -- signed
    balance_after NUMERIC(19,2) NOT NULL, held_after NUMERIC(19,2) NOT NULL,
    related_entry_id VARCHAR(36), ref_type VARCHAR(30), ref_id VARCHAR(36),
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    narration VARCHAR(300), created_by VARCHAR(36), created_by_name VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallet_entry_wallet_created ON wallet_entry (wallet_id, created_at DESC);
-- a hold closes once
CREATE UNIQUE INDEX uq_wallet_entry_hold_release ON wallet_entry (related_entry_id) WHERE type = 'HOLD_RELEASE';

CREATE TABLE topup_request (
    id VARCHAR(36) PRIMARY KEY,
    wallet_id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL,
    mode VARCHAR(14) NOT NULL,     -- GATEWAY|BANK_TRANSFER
    amount NUMERIC(19,2) NOT NULL, approved_amount NUMERIC(19,2),
    status VARCHAR(10) NOT NULL,   -- PENDING|APPROVED|REJECTED|FAILED|EXPIRED
    bank_ref VARCHAR(40), bank_ref_normalized VARCHAR(40) UNIQUE,
    proof_file_key VARCHAR(500), proof_file_name VARCHAR(255), proof_content_type VARCHAR(100),
    gateway_order_id VARCHAR(60) UNIQUE, gateway_payment_id VARCHAR(60),
    note VARCHAR(500), review_note VARCHAR(1000),
    requested_by VARCHAR(36), requested_by_name VARCHAR(150),
    reviewed_by VARCHAR(36), reviewed_at TIMESTAMPTZ, credit_entry_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_topup_request_status_created ON topup_request (status, created_at);
CREATE INDEX idx_topup_request_tenant ON topup_request (tenant_id, created_at DESC);

CREATE TABLE gateway_order (
    id VARCHAR(36) PRIMARY KEY,
    gateway_order_id VARCHAR(60) NOT NULL UNIQUE,
    purpose VARCHAR(14) NOT NULL,  -- TOPUP|SUBSCRIPTION
    ref_id VARCHAR(36),
    amount NUMERIC(19,2) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(10) NOT NULL,   -- CREATED|PAID|FAILED
    events JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE gateway_webhook_orphan (
    id VARCHAR(36) PRIMARY KEY,
    gateway_order_id VARCHAR(60) NOT NULL, event VARCHAR(60) NOT NULL,
    payload JSONB, attempts INT NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (gateway_order_id, event)
);

CREATE TABLE platform_receivable (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    amount NUMERIC(19,2) NOT NULL, original_amount NUMERIC(19,2) NOT NULL,
    reason VARCHAR(24) NOT NULL,   -- CANCEL_NET_NEGATIVE|CHARGEBACK|LATE_TICKET
    status VARCHAR(8) NOT NULL DEFAULT 'DUE',  -- DUE|SETTLED
    ref_type VARCHAR(30), ref_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), settled_at TIMESTAMPTZ
);
CREATE INDEX idx_platform_receivable_tenant_status ON platform_receivable (tenant_id, status, created_at);

-- every existing agency gets an OPEN zero wallet
INSERT INTO wallet (id, tenant_id)
SELECT gen_random_uuid()::text, id FROM tenant
ON CONFLICT (tenant_id) DO NOTHING;
