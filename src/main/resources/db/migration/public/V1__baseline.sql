-- Voyra platform (public schema): consolidated baseline.
-- Replaces public migrations V1-V38 for a fresh database (2026-11-13). Generated from a database built by the
-- old chain; tables, constraints and indexes are exactly that final state. Never edit after it is applied.

CREATE TABLE public.agency_application (
    id character varying(36) NOT NULL,
    status character varying(14) NOT NULL,
    agency_name character varying(150) NOT NULL,
    legal_name character varying(200),
    trade_name character varying(200),
    pan character varying(10) NOT NULL,
    gstin character varying(15),
    cin character varying(30),
    address character varying(500),
    city character varying(100),
    state_code character varying(2),
    pincode character varying(10),
    owner_name character varying(150) NOT NULL,
    owner_email character varying(150) NOT NULL,
    owner_phone character varying(30) NOT NULL,
    expected_monthly_volume character varying(40),
    base_currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    consent_at timestamp with time zone,
    upload_token_hash character varying(64) NOT NULL,
    upload_token_expires_at timestamp with time zone NOT NULL,
    submitted_at timestamp with time zone,
    reviewed_by character varying(36),
    reviewed_at timestamp with time zone,
    review_note character varying(2000),
    rejection_reason character varying(2000),
    tenant_id character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.agency_pricing (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    product character varying(10) NOT NULL,
    discount_pct numeric(5,2) NOT NULL,
    commission_pct numeric(5,2) NOT NULL,
    allow_commission_above_earned boolean DEFAULT false NOT NULL,
    effective_from timestamp with time zone NOT NULL,
    worst_case_net_per_10k numeric(19,2) NOT NULL,
    requires_second_approval boolean NOT NULL,
    created_by character varying(36) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_by character varying(36),
    approved_at timestamp with time zone,
    withdrawn_at timestamp with time zone,
    withdrawn_by character varying(36)
);

CREATE TABLE public.agency_pricing_assignment (
    tenant_id character varying(36) NOT NULL,
    product character varying(10) NOT NULL,
    discount_rule_id character varying(36) NOT NULL,
    commission_rule_id character varying(36) NOT NULL,
    updated_by character varying(36),
    updated_by_name character varying(150),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agency_pricing_assignment_product_check CHECK (((product)::text = ANY ((ARRAY['FLIGHT'::character varying, 'HOTEL'::character varying])::text[])))
);

CREATE TABLE public.agent (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    name character varying(150) NOT NULL,
    email character varying(150) NOT NULL,
    phone character varying(20),
    department character varying(30),
    password character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone,
    commission_rate numeric(5,2) DEFAULT 5.00 NOT NULL,
    manageable_services text[] DEFAULT '{}'::text[] NOT NULL,
    user_role character varying(20) DEFAULT 'AGENT'::character varying NOT NULL,
    deleted_at timestamp without time zone,
    password_changed_at timestamp with time zone
);

CREATE TABLE public.exchange_rate (
    id character varying(36) NOT NULL,
    rate_date date NOT NULL,
    base_currency character varying(3) NOT NULL,
    quote_currency character varying(3) NOT NULL,
    rate numeric(18,6) NOT NULL,
    source character varying(30) NOT NULL,
    fetched_at timestamp without time zone
);

CREATE TABLE public.feedback_link (
    token character varying(32) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    created_date timestamp without time zone,
    expires_at timestamp without time zone
);

CREATE TABLE public.gateway_order (
    id character varying(36) NOT NULL,
    gateway_order_id character varying(60) NOT NULL,
    purpose character varying(14) NOT NULL,
    ref_id character varying(36),
    amount numeric(19,2) NOT NULL,
    currency character(3) DEFAULT 'INR'::bpchar NOT NULL,
    status character varying(10) NOT NULL,
    events jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.gateway_webhook_orphan (
    id character varying(36) NOT NULL,
    gateway_order_id character varying(60) NOT NULL,
    event character varying(60) NOT NULL,
    payload jsonb,
    attempts integer DEFAULT 1 NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.inquiry (
    id character varying(36) NOT NULL,
    source character varying(40) NOT NULL,
    name character varying(150),
    email character varying(150),
    phone character varying(30),
    company character varying(200),
    plan character varying(60),
    message character varying(2000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_inquiry_contact CHECK (((email IS NOT NULL) OR (phone IS NOT NULL)))
);

CREATE TABLE public.kyc_document (
    id character varying(36) NOT NULL,
    application_id character varying(36) NOT NULL,
    type character varying(20) NOT NULL,
    file_key character varying(500) NOT NULL,
    file_name character varying(255) NOT NULL,
    content_type character varying(100),
    size_bytes bigint NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    note character varying(1000),
    reviewed_by character varying(36),
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.notification_outbox (
    id character varying(36) NOT NULL,
    kind character varying(40) NOT NULL,
    recipient character varying(150) NOT NULL,
    subject character varying(300) NOT NULL,
    body_html text,
    body_text text,
    status character varying(10) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    last_error character varying(500),
    created_at timestamp with time zone NOT NULL,
    sent_at timestamp with time zone,
    CONSTRAINT ck_notification_outbox_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENDING'::character varying, 'SENT'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.platform_admin (
    id character varying(36) NOT NULL,
    name character varying(150) NOT NULL,
    email character varying(150) NOT NULL,
    password character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone
);

CREATE TABLE public.platform_audit_log (
    id character varying(36) NOT NULL,
    actor_id character varying(36) NOT NULL,
    actor_name character varying(150) NOT NULL,
    action character varying(30) NOT NULL,
    entity_type character varying(30) NOT NULL,
    entity_id character varying(36) NOT NULL,
    detail jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.platform_booking (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    product character varying(8) NOT NULL,
    client_id character varying(36),
    lead_id character varying(36),
    service_id character varying(36),
    issued_by_agent_id character varying(36),
    status character varying(20) NOT NULL,
    environment character varying(4) NOT NULL,
    credential_id character varying(36),
    supplier_review_id character varying(120),
    supplier_booking_id character varying(120),
    pnr character varying(40),
    ticket_numbers text[],
    gross numeric(19,2),
    base numeric(19,2),
    discount_pct numeric(5,2),
    displayed numeric(19,2),
    pricing_snapshot jsonb,
    voyra_earned numeric(19,2),
    voyra_earned_source character varying(10),
    voyra_earned_actual numeric(19,2),
    voyra_earned_reversal numeric(19,2),
    agency_commission numeric(19,2),
    tds numeric(19,2),
    voyra_net numeric(19,2),
    pax_count integer,
    pax_enc text,
    itinerary jsonb,
    hold_entry_id character varying(36),
    debit_entry_id character varying(36),
    mirror_status character varying(8) DEFAULT 'NONE'::character varying NOT NULL,
    tenant_booking_id character varying(36),
    booked_by character varying(36),
    abort_reason character varying(300),
    supplier_message character varying(500),
    fare_rules text,
    review_expires_at timestamp with time zone,
    cancel_refund numeric(19,2),
    cancelled_at timestamp with time zone,
    last_polled_at timestamp with time zone,
    poll_count integer DEFAULT 0 NOT NULL,
    idempotency_key character varying(80),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    ticketed_at timestamp with time zone,
    confirm_payload_hash character varying(64),
    resolved_failed_at timestamp with time zone,
    pax_purged_at timestamp with time zone,
    ticket_details jsonb
);

CREATE TABLE public.platform_booking_event (
    id character varying(36) NOT NULL,
    platform_booking_id character varying(36) NOT NULL,
    type character varying(40) NOT NULL,
    payload jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.platform_booking_ticket (
    id character varying(36) NOT NULL,
    platform_booking_id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    seq integer NOT NULL,
    markup numeric(19,2) NOT NULL,
    fare_lines jsonb NOT NULL,
    total numeric(19,2) NOT NULL,
    created_by character varying(36) NOT NULL,
    created_by_name character varying(160),
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_booking_ticket_markup_check CHECK ((markup >= (0)::numeric))
);

CREATE TABLE public.platform_receivable (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    amount numeric(19,2) NOT NULL,
    original_amount numeric(19,2) NOT NULL,
    reason character varying(24) NOT NULL,
    status character varying(8) DEFAULT 'DUE'::character varying NOT NULL,
    ref_type character varying(30),
    ref_id character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    settled_at timestamp with time zone
);

CREATE TABLE public.platform_setting (
    key character varying(60) NOT NULL,
    value character varying(4000) NOT NULL,
    updated_by character varying(36),
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.platform_setting_request (
    id character varying(36) NOT NULL,
    setting_key character varying(60) NOT NULL,
    value character varying(4000) NOT NULL,
    requested_by character varying(36) NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    decided_by character varying(36),
    decided_at timestamp with time zone,
    outcome character varying(20)
);

CREATE TABLE public.platform_statement_import (
    id character varying(36) NOT NULL,
    file_name character varying(255) NOT NULL,
    file_sha256 character varying(64) NOT NULL,
    total_lines integer NOT NULL,
    applied_lines integer NOT NULL,
    duplicate_lines integer NOT NULL,
    unmatched_lines integer NOT NULL,
    imported_by character varying(36) NOT NULL,
    imported_by_name character varying(150),
    imported_at timestamp with time zone NOT NULL
);

CREATE TABLE public.platform_statement_line (
    id character varying(36) NOT NULL,
    import_id character varying(36) NOT NULL,
    line_no integer NOT NULL,
    supplier_booking_id character varying(120),
    pnr character varying(40),
    type character varying(6) NOT NULL,
    amount numeric(19,2) NOT NULL,
    commission numeric(19,2) NOT NULL,
    line_date date NOT NULL,
    status character varying(10) NOT NULL,
    reason character varying(300),
    platform_booking_id character varying(36),
    CONSTRAINT ck_platform_statement_line_status CHECK (((status)::text = ANY ((ARRAY['APPLIED'::character varying, 'DUPLICATE'::character varying, 'UNMATCHED'::character varying])::text[]))),
    CONSTRAINT ck_platform_statement_line_type CHECK (((type)::text = ANY ((ARRAY['SALE'::character varying, 'REFUND'::character varying])::text[])))
);

CREATE TABLE public.platform_supplier_credential (
    id character varying(36) NOT NULL,
    provider character varying(30) NOT NULL,
    environment character varying(10) NOT NULL,
    base_url character varying(255) NOT NULL,
    api_key_encrypted character varying(1024) NOT NULL,
    user_id_encrypted character varying(1024),
    is_active boolean DEFAULT false NOT NULL,
    updated_by character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    name character varying(80) NOT NULL
);

CREATE TABLE public.platform_supplier_product (
    credential_id character varying(36) NOT NULL,
    product character varying(10) NOT NULL,
    commission_estimate_pct numeric(5,2) DEFAULT 5 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(36),
    search_enabled boolean DEFAULT false NOT NULL,
    booking_enabled boolean DEFAULT false NOT NULL,
    CONSTRAINT platform_supplier_product_commission_estimate_pct_check CHECK (((commission_estimate_pct >= (0)::numeric) AND (commission_estimate_pct <= (100)::numeric))),
    CONSTRAINT platform_supplier_product_product_check CHECK (((product)::text = ANY ((ARRAY['FLIGHT'::character varying, 'HOTEL'::character varying])::text[])))
);

CREATE TABLE public.pricing_rule (
    id character varying(36) NOT NULL,
    kind character varying(10) NOT NULL,
    product character varying(10) NOT NULL,
    name character varying(60) NOT NULL,
    pct numeric(5,2) NOT NULL,
    allow_above_earned boolean DEFAULT false NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    created_by character varying(36) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(36),
    updated_by_name character varying(150),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pricing_rule_check CHECK ((((kind)::text = 'COMMISSION'::text) OR (NOT allow_above_earned))),
    CONSTRAINT pricing_rule_kind_check CHECK (((kind)::text = ANY ((ARRAY['DISCOUNT'::character varying, 'COMMISSION'::character varying])::text[]))),
    CONSTRAINT pricing_rule_pct_check CHECK (((pct >= (0)::numeric) AND (pct <= (100)::numeric))),
    CONSTRAINT pricing_rule_product_check CHECK (((product)::text = ANY ((ARRAY['FLIGHT'::character varying, 'HOTEL'::character varying])::text[])))
);

CREATE TABLE public.proposal_link (
    token character varying(32) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    created_date timestamp without time zone,
    expires_at timestamp without time zone
);

CREATE TABLE public.subscription (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    plan character varying(8) NOT NULL,
    skus text[] DEFAULT '{}'::text[] NOT NULL,
    seats integer DEFAULT 0 NOT NULL,
    status character varying(10) NOT NULL,
    period_start timestamp with time zone,
    period_end timestamp with time zone,
    trial_ends_at timestamp with time zone,
    waived_reason character varying(16),
    billing_cycle character varying(8) DEFAULT 'MONTHLY'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    expiry_notice_days integer,
    expiry_notice_for timestamp with time zone,
    plan_price_monthly numeric(12,2) DEFAULT 0 NOT NULL,
    seat_price_monthly numeric(12,2) DEFAULT 0 NOT NULL,
    CONSTRAINT subscription_billing_cycle_check CHECK (((billing_cycle)::text = ANY ((ARRAY['MONTHLY'::character varying, 'ANNUAL'::character varying])::text[]))),
    CONSTRAINT subscription_plan_check CHECK (((plan)::text = ANY ((ARRAY['STARTER'::character varying, 'LITE'::character varying, 'SUITE'::character varying])::text[]))),
    CONSTRAINT subscription_plan_price_monthly_check CHECK ((plan_price_monthly >= (0)::numeric)),
    CONSTRAINT subscription_seat_price_monthly_check CHECK ((seat_price_monthly >= (0)::numeric)),
    CONSTRAINT subscription_seats_check CHECK ((seats >= 0)),
    CONSTRAINT subscription_status_check CHECK (((status)::text = ANY ((ARRAY['TRIAL'::character varying, 'ACTIVE'::character varying, 'PAST_DUE'::character varying, 'READ_ONLY'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT subscription_waived_reason_check CHECK (((waived_reason)::text = ANY ((ARRAY['FOUNDING_AGENCY'::character varying, 'COMPLIMENTARY'::character varying, 'GMV_THRESHOLD'::character varying])::text[])))
);

CREATE TABLE public.subscription_invoice (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    number character varying(30),
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    lines jsonb DEFAULT '[]'::jsonb NOT NULL,
    amount numeric(19,2) NOT NULL,
    gst numeric(19,2) NOT NULL,
    total numeric(19,2) NOT NULL,
    status character varying(6) NOT NULL,
    due_at timestamp with time zone,
    gateway_order_id character varying(60),
    paid_at timestamp with time zone,
    paid_via character varying(10),
    pdf_key character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    paid_ref character varying(100),
    cgst numeric(19,2) DEFAULT 0 NOT NULL,
    sgst numeric(19,2) DEFAULT 0 NOT NULL,
    igst numeric(19,2) DEFAULT 0 NOT NULL,
    sac_code character varying(8),
    billing_details jsonb DEFAULT '{}'::jsonb NOT NULL,
    target_plan character varying(10),
    CONSTRAINT subscription_invoice_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ISSUED'::character varying, 'PAID'::character varying, 'VOID'::character varying])::text[]))),
    CONSTRAINT subscription_invoice_target_plan_check CHECK (((target_plan)::text = ANY ((ARRAY['LITE'::character varying, 'SUITE'::character varying])::text[])))
);

CREATE TABLE public.tenant (
    id character varying(36) NOT NULL,
    agency_name character varying(150) NOT NULL,
    owner_name character varying(150) NOT NULL,
    owner_email character varying(150) NOT NULL,
    password character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone,
    gst_number character varying(30),
    address character varying(500),
    currency character varying(10),
    default_commission numeric(5,2),
    logo_key character varying(500),
    state_code character varying(2),
    base_currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    default_sac_code character varying(10),
    invoice_terms character varying(2000),
    legal_name character varying(200),
    bank_account_name character varying(200),
    bank_account_number character varying(40),
    bank_ifsc_code character varying(20),
    bank_branch character varying(200),
    low_margin_threshold_percent numeric(5,2),
    contact_phone character varying(30),
    contact_email character varying(150),
    website character varying(150),
    status character varying(12) DEFAULT 'ACTIVE'::character varying NOT NULL,
    pan character varying(10),
    cin character varying(30),
    trade_name character varying(200),
    approved_at timestamp with time zone,
    approved_by character varying(36),
    booking_blocked boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_tenant_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'DEACTIVATED'::character varying])::text[])))
);

CREATE TABLE public.topup_request (
    id character varying(36) NOT NULL,
    wallet_id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    mode character varying(14) NOT NULL,
    amount numeric(19,2) NOT NULL,
    approved_amount numeric(19,2),
    status character varying(10) NOT NULL,
    bank_ref character varying(40),
    bank_ref_normalized character varying(40),
    proof_file_key character varying(500),
    proof_file_name character varying(255),
    proof_content_type character varying(100),
    gateway_order_id character varying(60),
    gateway_payment_id character varying(60),
    note character varying(500),
    review_note character varying(1000),
    requested_by character varying(36),
    requested_by_name character varying(150),
    reviewed_by character varying(36),
    reviewed_at timestamp with time zone,
    credit_entry_id character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.voyra_invoice_sequence (
    series character varying(20) NOT NULL,
    year integer NOT NULL,
    last_value bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.wallet (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    currency character(3) DEFAULT 'INR'::bpchar NOT NULL,
    balance numeric(19,2) DEFAULT 0 NOT NULL,
    held numeric(19,2) DEFAULT 0 NOT NULL,
    is_test boolean DEFAULT false NOT NULL,
    low_balance_threshold numeric(19,2),
    low_balance_notified_at timestamp with time zone,
    status character varying(8) DEFAULT 'OPEN'::character varying NOT NULL,
    closed_at timestamp with time zone,
    closed_by character varying(36),
    closure_utr character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT wallet_check CHECK (((held >= (0)::numeric) AND (balance >= held))),
    CONSTRAINT wallet_currency_check CHECK ((currency = 'INR'::bpchar)),
    CONSTRAINT wallet_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CLOSED'::character varying])::text[])))
);

CREATE TABLE public.wallet_entry (
    id character varying(36) NOT NULL,
    wallet_id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    type character varying(20) NOT NULL,
    amount numeric(19,2) NOT NULL,
    balance_after numeric(19,2) NOT NULL,
    held_after numeric(19,2) NOT NULL,
    related_entry_id character varying(36),
    ref_type character varying(30),
    ref_id character varying(36),
    idempotency_key character varying(120) NOT NULL,
    narration character varying(300),
    created_by character varying(36),
    created_by_name character varying(150),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.agency_application
    ADD CONSTRAINT agency_application_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.agency_pricing_assignment
    ADD CONSTRAINT agency_pricing_assignment_pkey PRIMARY KEY (tenant_id, product);

ALTER TABLE ONLY public.agency_pricing
    ADD CONSTRAINT agency_pricing_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.agent
    ADD CONSTRAINT agent_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.exchange_rate
    ADD CONSTRAINT exchange_rate_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_link
    ADD CONSTRAINT feedback_link_pkey PRIMARY KEY (token);

ALTER TABLE ONLY public.gateway_order
    ADD CONSTRAINT gateway_order_gateway_order_id_key UNIQUE (gateway_order_id);

ALTER TABLE ONLY public.gateway_order
    ADD CONSTRAINT gateway_order_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.gateway_webhook_orphan
    ADD CONSTRAINT gateway_webhook_orphan_gateway_order_id_event_key UNIQUE (gateway_order_id, event);

ALTER TABLE ONLY public.gateway_webhook_orphan
    ADD CONSTRAINT gateway_webhook_orphan_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.inquiry
    ADD CONSTRAINT inquiry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.kyc_document
    ADD CONSTRAINT kyc_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_outbox
    ADD CONSTRAINT notification_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_admin
    ADD CONSTRAINT platform_admin_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_audit_log
    ADD CONSTRAINT platform_audit_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_booking_event
    ADD CONSTRAINT platform_booking_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_booking
    ADD CONSTRAINT platform_booking_idempotency_key_key UNIQUE (idempotency_key);

ALTER TABLE ONLY public.platform_booking
    ADD CONSTRAINT platform_booking_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_booking
    ADD CONSTRAINT platform_booking_supplier_review_id_key UNIQUE (supplier_review_id);

ALTER TABLE ONLY public.platform_booking_ticket
    ADD CONSTRAINT platform_booking_ticket_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_booking_ticket
    ADD CONSTRAINT platform_booking_ticket_platform_booking_id_seq_key UNIQUE (platform_booking_id, seq);

ALTER TABLE ONLY public.platform_receivable
    ADD CONSTRAINT platform_receivable_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_setting
    ADD CONSTRAINT platform_setting_pkey PRIMARY KEY (key);

ALTER TABLE ONLY public.platform_setting_request
    ADD CONSTRAINT platform_setting_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_statement_import
    ADD CONSTRAINT platform_statement_import_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_statement_line
    ADD CONSTRAINT platform_statement_line_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_supplier_credential
    ADD CONSTRAINT platform_supplier_credential_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.platform_supplier_product
    ADD CONSTRAINT platform_supplier_product_pkey PRIMARY KEY (credential_id, product);

ALTER TABLE ONLY public.pricing_rule
    ADD CONSTRAINT pricing_rule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.proposal_link
    ADD CONSTRAINT proposal_link_pkey PRIMARY KEY (token);

ALTER TABLE ONLY public.subscription_invoice
    ADD CONSTRAINT subscription_invoice_number_key UNIQUE (number);

ALTER TABLE ONLY public.subscription_invoice
    ADD CONSTRAINT subscription_invoice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT subscription_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT subscription_tenant_id_key UNIQUE (tenant_id);

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.topup_request
    ADD CONSTRAINT topup_request_bank_ref_normalized_key UNIQUE (bank_ref_normalized);

ALTER TABLE ONLY public.topup_request
    ADD CONSTRAINT topup_request_gateway_order_id_key UNIQUE (gateway_order_id);

ALTER TABLE ONLY public.topup_request
    ADD CONSTRAINT topup_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.agency_pricing
    ADD CONSTRAINT uq_agency_pricing UNIQUE (tenant_id, product, effective_from);

ALTER TABLE ONLY public.voyra_invoice_sequence
    ADD CONSTRAINT voyra_invoice_sequence_pkey PRIMARY KEY (series, year);

ALTER TABLE ONLY public.wallet_entry
    ADD CONSTRAINT wallet_entry_idempotency_key_key UNIQUE (idempotency_key);

ALTER TABLE ONLY public.wallet_entry
    ADD CONSTRAINT wallet_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.wallet
    ADD CONSTRAINT wallet_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.wallet
    ADD CONSTRAINT wallet_tenant_id_key UNIQUE (tenant_id);

CREATE INDEX idx_agency_application_owner_email ON public.agency_application USING btree (lower((owner_email)::text));

CREATE INDEX idx_agency_application_status_created ON public.agency_application USING btree (status, created_at DESC);

CREATE INDEX idx_agency_pricing_assignment_commission ON public.agency_pricing_assignment USING btree (commission_rule_id);

CREATE INDEX idx_agency_pricing_assignment_discount ON public.agency_pricing_assignment USING btree (discount_rule_id);

CREATE UNIQUE INDEX idx_agent_email ON public.agent USING btree (lower((email)::text)) WHERE (deleted_at IS NULL);

CREATE INDEX idx_agent_tenant ON public.agent USING btree (tenant_id);

CREATE INDEX idx_agent_tenant_role ON public.agent USING btree (tenant_id, user_role);

CREATE INDEX idx_exchange_rate_pair_date ON public.exchange_rate USING btree (base_currency, quote_currency, rate_date);

CREATE INDEX idx_feedback_link_booking ON public.feedback_link USING btree (booking_id);

CREATE INDEX idx_feedback_link_tenant ON public.feedback_link USING btree (tenant_id);

CREATE INDEX idx_inquiry_created_at ON public.inquiry USING btree (created_at DESC);

CREATE INDEX idx_kyc_document_application ON public.kyc_document USING btree (application_id);

CREATE UNIQUE INDEX idx_platform_admin_email ON public.platform_admin USING btree (lower((email)::text));

CREATE INDEX idx_platform_audit_entity ON public.platform_audit_log USING btree (entity_type, entity_id, created_at DESC);

CREATE INDEX idx_platform_booking_event_booking ON public.platform_booking_event USING btree (platform_booking_id, created_at);

CREATE INDEX idx_platform_booking_pnr ON public.platform_booking USING btree (pnr);

CREATE INDEX idx_platform_booking_status ON public.platform_booking USING btree (status);

CREATE INDEX idx_platform_booking_supplier_booking_id ON public.platform_booking USING btree (supplier_booking_id);

CREATE INDEX idx_platform_booking_tenant_created ON public.platform_booking USING btree (tenant_id, created_at DESC);

CREATE INDEX idx_platform_booking_ticket_booking ON public.platform_booking_ticket USING btree (platform_booking_id);

CREATE INDEX idx_platform_booking_ticketed_at ON public.platform_booking USING btree (ticketed_at);

CREATE INDEX idx_platform_receivable_tenant_status ON public.platform_receivable USING btree (tenant_id, status, created_at);

CREATE INDEX idx_platform_statement_line_import ON public.platform_statement_line USING btree (import_id, line_no);

CREATE INDEX idx_proposal_link_tenant ON public.proposal_link USING btree (tenant_id);

CREATE INDEX idx_subscription_invoice_status_due ON public.subscription_invoice USING btree (status, due_at);

CREATE INDEX idx_subscription_invoice_tenant ON public.subscription_invoice USING btree (tenant_id, created_at DESC);

CREATE UNIQUE INDEX idx_tenant_owner_email ON public.tenant USING btree (lower((owner_email)::text));

CREATE INDEX idx_topup_request_status_created ON public.topup_request USING btree (status, created_at);

CREATE INDEX idx_topup_request_tenant ON public.topup_request USING btree (tenant_id, created_at DESC);

CREATE INDEX idx_wallet_entry_wallet_created ON public.wallet_entry USING btree (wallet_id, created_at DESC);

CREATE INDEX ix_notification_outbox_due ON public.notification_outbox USING btree (next_attempt_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENDING'::character varying])::text[]));

CREATE UNIQUE INDEX uq_exchange_rate_date_pair ON public.exchange_rate USING btree (rate_date, base_currency, quote_currency);

CREATE UNIQUE INDEX uq_platform_setting_request_open ON public.platform_setting_request USING btree (setting_key) WHERE (decided_at IS NULL);

CREATE UNIQUE INDEX uq_platform_supplier_credential_name ON public.platform_supplier_credential USING btree (lower((name)::text));

CREATE UNIQUE INDEX uq_pricing_rule_default ON public.pricing_rule USING btree (kind, product) WHERE is_default;

CREATE UNIQUE INDEX uq_pricing_rule_name ON public.pricing_rule USING btree (kind, product, lower((name)::text));

CREATE UNIQUE INDEX uq_wallet_entry_hold_release ON public.wallet_entry USING btree (related_entry_id) WHERE ((type)::text = 'HOLD_RELEASE'::text);

CREATE UNIQUE INDEX ux_platform_statement_line_applied ON public.platform_statement_line USING btree (platform_booking_id, type) WHERE ((status)::text = 'APPLIED'::text);

ALTER TABLE ONLY public.agency_pricing_assignment
    ADD CONSTRAINT agency_pricing_assignment_commission_rule_id_fkey FOREIGN KEY (commission_rule_id) REFERENCES public.pricing_rule(id);

ALTER TABLE ONLY public.agency_pricing_assignment
    ADD CONSTRAINT agency_pricing_assignment_discount_rule_id_fkey FOREIGN KEY (discount_rule_id) REFERENCES public.pricing_rule(id);

ALTER TABLE ONLY public.platform_booking_ticket
    ADD CONSTRAINT platform_booking_ticket_platform_booking_id_fkey FOREIGN KEY (platform_booking_id) REFERENCES public.platform_booking(id);

ALTER TABLE ONLY public.platform_supplier_product
    ADD CONSTRAINT platform_supplier_product_credential_id_fkey FOREIGN KEY (credential_id) REFERENCES public.platform_supplier_credential(id);

-- ---------------------------------------------------------------------------
-- Seed rows (the rows the old chain inserted)
-- ---------------------------------------------------------------------------

INSERT INTO platform_setting (key, value, updated_at) VALUES
    ('commission_estimate_pct', '5', now()),
    ('discount_base', 'GROSS', now()),
    ('founding_agencies', '["Voyra Global"]', now()),
    ('pax_retention_days', '90', now()),
    ('reference_fare_basket', '[{"label":"Domestic LCC","gross":5000,"base":3500},{"label":"Domestic FSC","gross":9000,"base":6000},{"label":"International","gross":45000,"base":22000}]', now()),
    ('reference_fare_basket_hotel', '[{"label":"Budget 3*","gross":3150,"base":3000},{"label":"Mid 4*","gross":9440,"base":8000},{"label":"Premium 5*","gross":23600,"base":20000}]', now()),
    ('sku_prices', '{"LITE": 2999, "VISA": 499, "SUITE": 4999, "REPORTS": 699, "CRM_CORE": 2499, "STAFF_SEATS": 399, "ACCOUNTS_GST": 1999}', now()),
    ('subscription_sac', '998314', now()),
    ('tds_rate', '0', now()),
    ('tripjack_live_ready', 'false', now()),
    ('voyra_address', '', now()),
    ('voyra_cancel_fee', '0', now()),
    ('voyra_gstin', '', now()),
    ('voyra_legal_name', '', now()),
    ('voyra_state_code', '', now());

-- Default pricing rules at 0 %: an agency with no assignment books on these.
INSERT INTO pricing_rule (id, kind, product, name, pct, is_default, created_by, updated_by_name)
SELECT gen_random_uuid()::text, k, p, 'Default', 0, true, 'system', 'System'
FROM (VALUES ('DISCOUNT'), ('COMMISSION')) kinds (k), (VALUES ('FLIGHT'), ('HOTEL')) products (p);

-- The mock supplier for local QA; every switch off. A MOCK row cannot be created on the cloudrun profile.
INSERT INTO platform_supplier_credential (id, name, provider, environment, base_url, api_key_encrypted, is_active, created_at, updated_at)
VALUES (gen_random_uuid()::text, 'Mock supplier', 'MOCK', 'MOCK', 'mock://local', '', false, now(), now());
INSERT INTO platform_supplier_product (credential_id, product)
SELECT c.id, p.product FROM platform_supplier_credential c, (VALUES ('FLIGHT'), ('HOTEL')) p (product)
WHERE c.provider = 'MOCK' AND c.name = 'Mock supplier';
