-- Voyra agency (tenant schema): consolidated baseline.
-- Replaces tenant migrations V1-V79 for a fresh database (2026-11-13). TenantFlywayMigrator runs it in each
-- tenant_<id> schema with search_path "tenant, public" (pg_trgm lives in public). Generated from a schema built by the
-- old chain; tables, constraints and indexes are exactly that final state. Never edit after it is applied.

CREATE TABLE accounting_change_alert (
    id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    field_name character varying(60) NOT NULL,
    old_value character varying(500),
    new_value character varying(500),
    raised_at timestamp without time zone NOT NULL,
    raised_by character varying(36),
    acknowledged boolean DEFAULT false NOT NULL,
    acknowledged_at timestamp without time zone,
    acknowledged_by character varying(36)
);

CREATE TABLE agency_setting (
    id character varying(36) NOT NULL,
    kind character varying(30) NOT NULL,
    service_type character varying(20),
    name character varying(150) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE agency_setting_supplier_backup (
    id character varying(36),
    kind character varying(30),
    service_type character varying(20),
    name character varying(150),
    is_active boolean,
    is_default boolean,
    sort_order integer,
    created_at timestamp without time zone
);

CREATE TABLE audit_log (
    id character varying(36) NOT NULL,
    entity_type character varying(30) NOT NULL,
    entity_id character varying(36) NOT NULL,
    entity_label character varying(200),
    action character varying(10) NOT NULL,
    actor_id character varying(36) NOT NULL,
    actor_name character varying(150) NOT NULL,
    field_changes jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE TABLE bank_account (
    id character varying(36) NOT NULL,
    account_name character varying(120) NOT NULL,
    account_number character varying(40),
    ifsc character varying(20),
    branch character varying(120),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    ledger_account_code character varying(10) NOT NULL,
    opening_balance numeric(19,2) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE bank_match_rule (
    id character varying(36) NOT NULL,
    name character varying(120) NOT NULL,
    match_field character varying(20) NOT NULL,
    pattern character varying(200) NOT NULL,
    target_account_code character varying(10) NOT NULL,
    auto_post boolean DEFAULT false NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    deleted_at timestamp without time zone
);

CREATE TABLE bank_statement_import (
    id character varying(36) NOT NULL,
    bank_account_id character varying(36) NOT NULL,
    profile_id character varying(36) NOT NULL,
    file_name character varying(255),
    storage_key character varying(500),
    period_from date,
    period_to date,
    row_count integer DEFAULT 0 NOT NULL,
    duplicate_count integer DEFAULT 0 NOT NULL,
    status character varying(20) NOT NULL,
    imported_at timestamp without time zone,
    imported_by character varying(36)
);

CREATE TABLE bank_statement_profile (
    id character varying(36) NOT NULL,
    bank_name character varying(120) NOT NULL,
    file_format character varying(20) DEFAULT 'CSV'::character varying NOT NULL,
    date_format character varying(30) NOT NULL,
    column_map text NOT NULL,
    amount_convention character varying(30) NOT NULL,
    created_date timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE bank_transaction (
    id character varying(36) NOT NULL,
    import_id character varying(36) NOT NULL,
    bank_account_id character varying(36) NOT NULL,
    txn_date date NOT NULL,
    value_date date,
    description character varying(500),
    bank_reference character varying(100),
    amount numeric(19,2) NOT NULL,
    direction character varying(10) NOT NULL,
    running_balance numeric(19,2),
    match_status character varying(20) DEFAULT 'UNMATCHED'::character varying NOT NULL,
    matched_source_type character varying(30),
    matched_source_id character varying(36),
    match_score numeric(5,2),
    matched_at timestamp without time zone,
    matched_by character varying(36)
);

CREATE SEQUENCE booking_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE booking (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    agent_id character varying(36) NOT NULL,
    agent_name character varying(150) NOT NULL,
    type character varying(20) NOT NULL,
    destination character varying(150) NOT NULL,
    pnr character varying(100),
    ticket_no character varying(50),
    airline character varying(100),
    supplier character varying(150),
    journey_date date,
    return_date date,
    trip_type character varying(20),
    net_cost numeric(19,2) DEFAULT 0 NOT NULL,
    selling_price numeric(19,2) DEFAULT 0 NOT NULL,
    profit numeric(19,2) DEFAULT 0 NOT NULL,
    booking_status character varying(20) NOT NULL,
    payment_status character varying(20) NOT NULL,
    booking_date date,
    cancel_reason character varying(255),
    refund_status character varying(100),
    created_date timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    refund_state character varying(20) DEFAULT 'NOT_APPLICABLE'::character varying NOT NULL,
    refund_amount numeric(19,2),
    refund_due_date date,
    refunded_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    cancelled_by character varying(36),
    ticketing_deadline date,
    cancellation_deadline date,
    deadline_note character varying(255),
    primary_invoice_id character varying(36),
    invoiced_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    received_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    refunded_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    payment_status_source character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    lead_id character varying(36),
    service_id character varying(36),
    service_type character varying(20),
    service_label character varying(200),
    service_agent_id character varying(36),
    service_agent_name character varying(150),
    notes text,
    flight_number character varying(20),
    flight_from character varying(100),
    flight_to character varying(100),
    flight_cabin character varying(20),
    hotel_confirmation_no character varying(100),
    hotel_name character varying(200),
    hotel_city character varying(150),
    hotel_check_in date,
    hotel_check_out date,
    hotel_room_type character varying(100),
    hotel_board_basis character varying(50),
    hotel_rooms integer,
    visa_application_no character varying(100),
    visa_country character varying(100),
    visa_applied_date date,
    visa_appointment_date date,
    visa_issued_date date,
    transfer_voucher_no character varying(100),
    transfer_vehicle_type character varying(50),
    transfer_pickup character varying(150),
    transfer_dropoff character varying(150),
    transfer_date date,
    transfer_time character varying(5),
    hotel_country_code character varying(2),
    is_international boolean DEFAULT false NOT NULL,
    vendor_id character varying(36),
    markup_mode character varying(10),
    markup_value numeric(19,4),
    departure_date date,
    cancellation_supplier_charge numeric(19,2) DEFAULT 0 NOT NULL,
    cancellation_agency_fee numeric(19,2) DEFAULT 0 NOT NULL,
    customer_refund_due numeric(19,2) DEFAULT 0 NOT NULL,
    supplier_refund_expected numeric(19,2) DEFAULT 0 NOT NULL,
    supplier_refund_received numeric(19,2) DEFAULT 0 NOT NULL,
    cancellation_type character varying(20),
    refund_checked_at timestamp without time zone,
    refund_checked_by character varying(255),
    booking_number bigint DEFAULT nextval('booking_number_seq'::regclass) NOT NULL,
    flight_depart_time character varying(5),
    flight_arrive_time character varying(5),
    flight_arrive_date date,
    supplier_refund_claim_amount numeric(14,2),
    supplier_refund_claim_on date,
    supplier_refund_claim_reference character varying(100),
    supplier_refund_claim_by character varying(150),
    supplier_refund_claim_at timestamp without time zone,
    deleted_at timestamp without time zone,
    cancellation_airline_charge numeric(19,2) DEFAULT 0 NOT NULL,
    accountant_remark character varying(255),
    commission numeric(19,2) DEFAULT 0 NOT NULL,
    platform_booking_id character varying(36)
);

CREATE TABLE booking_cost_component (
    id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    booking_passenger_id character varying(36),
    service_type character varying(20) NOT NULL,
    vendor_id character varying(36),
    vendor_name character varying(120),
    description character varying(200),
    net_cost numeric(19,2) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1 NOT NULL,
    net_cost_inr numeric(19,2) DEFAULT 0 NOT NULL,
    due_date date,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    deleted_at timestamp without time zone
);

CREATE TABLE booking_document (
    id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    doc_type character varying(30) NOT NULL,
    reference_number character varying(100),
    issued_date date,
    file_key character varying(500),
    file_name character varying(255),
    content_type character varying(150),
    notes text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    net_cost numeric(14,2) DEFAULT 0 NOT NULL,
    selling_price numeric(14,2) DEFAULT 0 NOT NULL,
    markup_mode character varying(10),
    markup_value numeric(14,2),
    vendor_id character varying(36),
    supplier_invoice_id character varying(36),
    deleted_at timestamp without time zone,
    details jsonb,
    purchase_currency character varying(3),
    purchase_price numeric(14,2),
    purchase_rate numeric(18,6),
    markup_currency character varying(3),
    markup_input numeric(14,2),
    markup_rate numeric(18,6),
    charges jsonb,
    commission_mode character varying(10),
    commission_value numeric(14,2),
    supplier_tds_rate numeric(5,2),
    commission numeric(14,2) DEFAULT 0 NOT NULL,
    supplier_tds numeric(14,2) DEFAULT 0 NOT NULL,
    commission_inr numeric(14,2) DEFAULT 0 NOT NULL,
    travellers jsonb,
    legs jsonb
);

CREATE TABLE booking_passenger (
    id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    lead_member_id character varying(36),
    passenger_name character varying(150) NOT NULL,
    pax_type character varying(10),
    fare_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    tax_amount numeric(19,2) DEFAULT 0 NOT NULL,
    deleted_at timestamp without time zone
);

CREATE TABLE booking_sector (
    id character varying(36) NOT NULL,
    booking_passenger_id character varying(36) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    sector_from character varying(20),
    sector_to character varying(20),
    flight_number character varying(20),
    travel_date date,
    cabin_class character varying(20),
    created_at timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE branch (
    id character varying(36) NOT NULL,
    name character varying(120) NOT NULL,
    code character varying(20),
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone
);

CREATE TABLE client (
    id character varying(36) NOT NULL,
    identifier character varying(150) NOT NULL,
    name character varying(150) NOT NULL,
    type character varying(10) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    modified_at timestamp without time zone,
    modified_by character varying(36),
    possible_duplicate_of character varying(36),
    gstin character varying(20),
    state_code character varying(2),
    billing_address character varying(500),
    is_overseas boolean DEFAULT false NOT NULL,
    credit_limit numeric(14,2)
);

CREATE TABLE client_invoice (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    agent_id character varying(36) NOT NULL,
    amount numeric(19,2) DEFAULT 0 NOT NULL,
    gst numeric(19,2) DEFAULT 0 NOT NULL,
    total_with_gst numeric(19,2) DEFAULT 0 NOT NULL,
    amount_paid numeric(19,2) DEFAULT 0 NOT NULL,
    status character varying(20) NOT NULL,
    invoice_date date,
    due_date date,
    payment_mode character varying(50),
    created_date timestamp without time zone,
    lead_id character varying(36),
    service_id character varying(36),
    service_label character varying(200),
    description character varying(255),
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

CREATE TABLE communication_log (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    lead_id character varying(36),
    member_id character varying(36),
    channel character varying(20) NOT NULL,
    direction character varying(10) NOT NULL,
    subject character varying(200),
    summary character varying(2000) NOT NULL,
    occurred_at timestamp without time zone NOT NULL,
    duration_minutes integer,
    outcome character varying(200),
    file_key character varying(500),
    file_name character varying(255),
    content_type character varying(150),
    actor_id character varying(36) NOT NULL,
    actor_name character varying(150) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    deleted_at timestamp without time zone
);

CREATE TABLE credit_note (
    id character varying(36) NOT NULL,
    credit_note_number character varying(40),
    financial_year character varying(9),
    invoice_id character varying(36) NOT NULL,
    invoice_number character varying(40),
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    booking_id character varying(36),
    reason character varying(30) NOT NULL,
    reason_note character varying(500),
    status character varying(20) NOT NULL,
    currency_code character varying(3) NOT NULL,
    fx_rate_to_inr numeric(18,6) NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    tcs_amount numeric(19,2) DEFAULT 0 NOT NULL,
    cancellation_fee numeric(19,2) DEFAULT 0 NOT NULL,
    total_amount numeric(19,2) DEFAULT 0 NOT NULL,
    total_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    refundable_amount numeric(19,2) DEFAULT 0 NOT NULL,
    refunded_amount numeric(19,2) DEFAULT 0 NOT NULL,
    note_date date NOT NULL,
    issued_at timestamp without time zone,
    issued_by character varying(36),
    cancelled_at timestamp without time zone,
    cancelled_by character varying(36),
    cancel_reason character varying(255),
    pdf_file_key character varying(500),
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    branch_id character varying(36),
    supplier_retained numeric(19,2)
);

CREATE TABLE customer_ledger_entry (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    entry_date date NOT NULL,
    entry_type character varying(30) NOT NULL,
    source_type character varying(30) NOT NULL,
    source_id character varying(36) NOT NULL,
    document_number character varying(40),
    narration character varying(255) NOT NULL,
    booking_id character varying(36),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    debit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    debit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    reversed_entry_id character varying(36),
    created_at timestamp without time zone,
    created_by character varying(36)
);

CREATE TABLE document_number_sequence (
    id character varying(36) NOT NULL,
    document_kind character varying(40) NOT NULL,
    financial_year character varying(9) NOT NULL,
    prefix character varying(12) NOT NULL,
    last_value bigint DEFAULT 0 NOT NULL,
    padding integer DEFAULT 4 NOT NULL,
    updated_at timestamp without time zone
);

CREATE TABLE feedback (
    id character varying(36) NOT NULL,
    booking_id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    rating integer NOT NULL,
    comment character varying(2000),
    submitted_at timestamp without time zone NOT NULL
);

CREATE TABLE invoice (
    id character varying(36) NOT NULL,
    invoice_number character varying(40),
    financial_year character varying(9),
    document_type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    client_gstin character varying(20),
    client_state_code character varying(2),
    billing_address character varying(500),
    agency_legal_name character varying(200),
    agency_gstin character varying(20),
    agency_state_code character varying(2),
    agency_address character varying(500),
    booking_id character varying(36),
    lead_id character varying(36),
    agent_id character varying(36) NOT NULL,
    place_of_supply_code character varying(2),
    supply_nature character varying(30),
    tax_treatment character varying(20),
    place_of_supply_override character varying(2),
    export_of_service_requested boolean DEFAULT false NOT NULL,
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    fx_rate_source character varying(30),
    fx_locked_at timestamp without time zone,
    subtotal numeric(19,2) DEFAULT 0 NOT NULL,
    discount_total numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    gst_total numeric(19,2) DEFAULT 0 NOT NULL,
    tcs_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    tcs_section character varying(20),
    tcs_base_amount numeric(19,2) DEFAULT 0 NOT NULL,
    tcs_amount numeric(19,2) DEFAULT 0 NOT NULL,
    round_off numeric(19,2) DEFAULT 0 NOT NULL,
    grand_total numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_value_inr numeric(19,2) DEFAULT 0 NOT NULL,
    gst_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    tcs_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    grand_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    amount_received numeric(19,2) DEFAULT 0 NOT NULL,
    credit_note_total numeric(19,2) DEFAULT 0 NOT NULL,
    balance_due numeric(19,2) DEFAULT 0 NOT NULL,
    balance_due_inr numeric(19,2) DEFAULT 0 NOT NULL,
    invoice_date date,
    due_date date,
    issued_at timestamp without time zone,
    issued_by character varying(36),
    cancelled_at timestamp without time zone,
    cancelled_by character varying(36),
    cancel_reason character varying(255),
    supersedes_invoice_id character varying(36),
    notes character varying(1000),
    terms character varying(2000),
    pdf_file_key character varying(500),
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    service_category character varying(20),
    other_tax_total numeric(19,2) DEFAULT 0 NOT NULL,
    other_tax_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    branch_id character varying(36),
    billing_model character varying(20),
    deleted_at timestamp without time zone,
    pass_through_amount numeric(19,2),
    uploaded_file_name character varying(255),
    uploaded_content_type character varying(150)
);

CREATE TABLE invoice_line_item (
    id character varying(36) NOT NULL,
    invoice_id character varying(36) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    description character varying(500) NOT NULL,
    sac_code character varying(10),
    service_type character varying(30),
    quantity numeric(12,3) DEFAULT 1 NOT NULL,
    unit_price numeric(19,2) DEFAULT 0 NOT NULL,
    line_subtotal numeric(19,2) DEFAULT 0 NOT NULL,
    discount_amount numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_percent numeric(6,3) DEFAULT 100.000 NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    gst_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    cgst_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    sgst_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    igst_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    line_total numeric(19,2) DEFAULT 0 NOT NULL,
    tax_rate_config_id character varying(36),
    created_at timestamp without time zone,
    fare_amount numeric(19,2),
    tax_amount numeric(19,2),
    deleted_at timestamp without time zone,
    booking_id character varying(36),
    booking_document_id character varying(36),
    charges jsonb,
    travellers jsonb,
    legs jsonb,
    purchase_currency character varying(3),
    purchase_rate numeric(18,6)
);

CREATE TABLE invoice_tax (
    id character varying(36) NOT NULL,
    invoice_id character varying(36) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    label character varying(150) NOT NULL,
    tax_rate_config_id character varying(36),
    tax_kind character varying(10),
    mode character varying(10) NOT NULL,
    rate_percent numeric(6,3),
    flat_amount numeric(19,2),
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    amount numeric(19,2) DEFAULT 0 NOT NULL,
    visible_to_customer boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE journal_entry (
    id character varying(36) NOT NULL,
    entry_number character varying(40) NOT NULL,
    entry_date date NOT NULL,
    financial_year character varying(9) NOT NULL,
    source_type character varying(30) NOT NULL,
    source_id character varying(50),
    purpose character varying(40) NOT NULL,
    narration character varying(255) NOT NULL,
    booking_id character varying(36),
    branch_id character varying(36),
    status character varying(20) NOT NULL,
    reverses_entry_id character varying(36),
    created_at timestamp without time zone,
    created_by character varying(36)
);

CREATE TABLE journal_line (
    id character varying(36) NOT NULL,
    journal_entry_id character varying(36) NOT NULL,
    line_no integer NOT NULL,
    account_code character varying(10) NOT NULL,
    party_type character varying(20),
    party_id character varying(36),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1 NOT NULL,
    debit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    debit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    narration character varying(255),
    CONSTRAINT chk_journal_line_one_side CHECK (((debit_amount = (0)::numeric) OR (credit_amount = (0)::numeric)))
);

CREATE TABLE lead (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    client_type character varying(10) NOT NULL,
    destination character varying(150) NOT NULL,
    travel_date_from date,
    travel_date_to date,
    adults integer DEFAULT 1 NOT NULL,
    kids integer DEFAULT 0 NOT NULL,
    kid_ages integer[] DEFAULT '{}'::integer[] NOT NULL,
    total_travellers integer DEFAULT 1 NOT NULL,
    lead_description text,
    preferences text,
    current_status character varying(20) NOT NULL,
    source character varying(100) NOT NULL,
    priority character varying(10) NOT NULL,
    categories text[] DEFAULT '{}'::text[] NOT NULL,
    budget character varying(50),
    follow_up_date date,
    lost_reason character varying(255),
    public_proposal_token character varying(32),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    special_notes text,
    travel_preferences text[] DEFAULT '{}'::text[] NOT NULL,
    quoted_net_total numeric(19,2) DEFAULT 0 NOT NULL,
    quoted_selling_total numeric(19,2) DEFAULT 0 NOT NULL,
    open_follow_ups integer DEFAULT 0 NOT NULL,
    service_count integer DEFAULT 0 NOT NULL,
    created_by_name character varying(150),
    proposal_locked boolean DEFAULT false NOT NULL,
    escalated boolean DEFAULT false NOT NULL,
    escalated_at timestamp without time zone,
    escalation_reason character varying(255)
);

CREATE TABLE lead_follow_up (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    lead_destination character varying(150) NOT NULL,
    service_type character varying(20),
    due_date date NOT NULL,
    note character varying(500) NOT NULL,
    assigned_agent_id character varying(36) NOT NULL,
    assigned_agent_name character varying(150) NOT NULL,
    status character varying(10) NOT NULL,
    completed_at timestamp without time zone,
    created_at timestamp without time zone,
    created_by character varying(36),
    deleted_at timestamp without time zone
);

CREATE TABLE lead_members (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    member_id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    member_name character varying(150) NOT NULL,
    status character varying(15) NOT NULL,
    dropped_reason character varying(255),
    created_at timestamp without time zone,
    created_by character varying(36),
    modified_at timestamp without time zone,
    modified_by character varying(36),
    fare_class character varying(10)
);

CREATE TABLE lead_notes (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    author_agent_id character varying(36) NOT NULL,
    author_name character varying(150) NOT NULL,
    text text NOT NULL,
    created_at timestamp without time zone
);

CREATE TABLE lead_proposal (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    type character varying(20) NOT NULL,
    description character varying(255) NOT NULL,
    supplier character varying(150),
    net_cost numeric(19,2) DEFAULT 0 NOT NULL,
    selling_price numeric(19,2) DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    service_id character varying(36),
    service_label character varying(200),
    option_group character varying(36),
    is_selected boolean DEFAULT false NOT NULL,
    selected_by character varying(20),
    selected_at timestamp without time zone,
    markup_mode character varying(10),
    markup_value numeric(19,4),
    deleted_at timestamp without time zone,
    purchase_currency character varying(3),
    purchase_price numeric(14,2),
    purchase_rate numeric(18,6),
    markup_currency character varying(3),
    markup_input numeric(14,2),
    markup_rate numeric(18,6)
);

CREATE TABLE lead_service (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    lead_destination character varying(150) NOT NULL,
    lead_status character varying(20) NOT NULL,
    type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    label character varying(200) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    assigned_agent_id character varying(36),
    assigned_agent_name character varying(150),
    preferences text[] DEFAULT '{}'::text[] NOT NULL,
    due_date date,
    date_from date,
    date_to date,
    net_total numeric(19,2) DEFAULT 0 NOT NULL,
    selling_total numeric(19,2) DEFAULT 0 NOT NULL,
    flight_trip_type character varying(20),
    flight_cabin character varying(20),
    flight_sectors jsonb DEFAULT '[]'::jsonb NOT NULL,
    hotel_city character varying(150),
    hotel_check_in date,
    hotel_check_out date,
    hotel_nights integer,
    hotel_rooms integer,
    visa_source_city character varying(150),
    visa_source_country character varying(100),
    visa_country character varying(100),
    visa_intended_travel_date date,
    visa_appointment_date date,
    visa_checklists jsonb DEFAULT '{}'::jsonb NOT NULL,
    transfer_vehicle_type character varying(50),
    transfer_pickup character varying(150),
    transfer_dropoff character varying(150),
    transfer_date date,
    transfer_time character varying(5),
    transfer_passengers integer,
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    hotel_country_code character varying(2),
    deleted_at timestamp without time zone
);

CREATE TABLE lead_timeline (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    event_type character varying(30) NOT NULL,
    from_status character varying(20),
    to_status character varying(20),
    actor_agent_id character varying(36) NOT NULL,
    actor_name character varying(150) NOT NULL,
    description character varying(500) NOT NULL,
    created_at timestamp without time zone,
    service_id character varying(36)
);

CREATE TABLE lead_voucher (
    id character varying(36) NOT NULL,
    lead_id character varying(36) NOT NULL,
    service_id character varying(36),
    service_label character varying(200),
    supplier character varying(150) NOT NULL,
    reference_number character varying(100) NOT NULL,
    voucher_date date NOT NULL,
    file_key character varying(500),
    notes text,
    created_at timestamp without time zone,
    created_by character varying(36),
    file_name character varying(255),
    content_type character varying(150)
);

CREATE TABLE ledger_account (
    id character varying(36) NOT NULL,
    code character varying(10) NOT NULL,
    name character varying(120) NOT NULL,
    account_type character varying(20) NOT NULL,
    parent_code character varying(10),
    is_system boolean DEFAULT false NOT NULL,
    is_control boolean DEFAULT false NOT NULL,
    control_of character varying(30),
    is_active boolean DEFAULT true NOT NULL,
    created_date timestamp without time zone
);

CREATE TABLE markup_default (
    service_type character varying(20) NOT NULL,
    mode character varying(10) NOT NULL,
    value numeric(19,4) DEFAULT 0 NOT NULL,
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

CREATE TABLE member (
    member_id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    name character varying(150) NOT NULL,
    email character varying(150),
    country_code character varying(6),
    phone character varying(20),
    type character varying(10) NOT NULL,
    dob date,
    gender character varying(30),
    relation character varying(20) NOT NULL,
    nationality character varying(100),
    passport_number character varying(20),
    passport_expiry date,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    modified_at timestamp without time zone,
    modified_by character varying(36)
);

CREATE TABLE member_documents (
    id character varying(36) NOT NULL,
    member_id character varying(36) NOT NULL,
    name character varying(255) NOT NULL,
    file_key character varying(500) NOT NULL,
    doc_type character varying(50),
    uploaded_at timestamp without time zone,
    uploaded_by character varying(36),
    content_type character varying(150),
    deleted_at timestamp without time zone
);

CREATE TABLE payment_receipt (
    id character varying(36) NOT NULL,
    receipt_number character varying(40),
    financial_year character varying(9),
    direction character varying(10) NOT NULL,
    invoice_id character varying(36),
    credit_note_id character varying(36),
    client_id character varying(36) NOT NULL,
    booking_id character varying(36),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    amount numeric(19,2) DEFAULT 0 NOT NULL,
    amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    payment_mode character varying(20) NOT NULL,
    instrument_ref character varying(100),
    bank_account_label character varying(150),
    received_on date NOT NULL,
    is_advance boolean DEFAULT false NOT NULL,
    reverses_receipt_id character varying(36),
    reversed_at timestamp without time zone,
    reversed_by character varying(36),
    reversal_reason character varying(255),
    notes character varying(500),
    created_at timestamp without time zone,
    created_by character varying(36),
    applied_from_advance boolean DEFAULT false NOT NULL,
    branch_id character varying(36),
    gateway_provider character varying(30),
    gateway_txn_ref character varying(80),
    gateway_fee numeric(19,2),
    gateway_fee_inr numeric(19,2),
    net_deposit_inr numeric(19,2),
    bank_account_id character varying(36)
);

CREATE TABLE shift_handover (
    id character varying(36) NOT NULL,
    from_agent_id character varying(36) NOT NULL,
    from_agent_name character varying(150) NOT NULL,
    to_agent_id character varying(36),
    to_agent_name character varying(150),
    summary character varying(2000) NOT NULL,
    pinned_lead_ids text[],
    pinned_booking_ids text[],
    shift_ended_at timestamp without time zone NOT NULL,
    acknowledged_at timestamp without time zone,
    acknowledged_by character varying(36),
    acknowledged_by_name character varying(150),
    created_at timestamp without time zone NOT NULL
);

CREATE TABLE supplier_credential (
    id character varying(36) NOT NULL,
    provider character varying(30) NOT NULL,
    environment character varying(10) NOT NULL,
    base_url character varying(255) NOT NULL,
    api_key_encrypted character varying(1024) NOT NULL,
    user_id_encrypted character varying(1024),
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

CREATE TABLE supplier_credit_note (
    id character varying(36) NOT NULL,
    supplier_note_number character varying(60),
    vendor_id character varying(36) NOT NULL,
    vendor_name character varying(150) NOT NULL,
    supplier_invoice_id character varying(36) NOT NULL,
    supplier_invoice_number character varying(60),
    booking_id character varying(36),
    reason character varying(30) NOT NULL,
    reason_note character varying(500),
    status character varying(20) NOT NULL,
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    retention_fee numeric(19,2) DEFAULT 0 NOT NULL,
    total_amount numeric(19,2) DEFAULT 0 NOT NULL,
    total_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    refundable_amount numeric(19,2) DEFAULT 0 NOT NULL,
    refunded_amount numeric(19,2) DEFAULT 0 NOT NULL,
    note_date date NOT NULL,
    recorded_at timestamp without time zone,
    recorded_by character varying(36),
    cancelled_at timestamp without time zone,
    cancelled_by character varying(36),
    cancel_reason character varying(255),
    file_key character varying(500),
    file_name character varying(255),
    content_type character varying(150),
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

CREATE TABLE supplier_invoice (
    id character varying(36) NOT NULL,
    supplier_name character varying(150) NOT NULL,
    category character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    due_date date,
    created_date timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    vendor_id character varying(36),
    kind character varying(20) DEFAULT 'PURCHASE'::character varying NOT NULL,
    supplier_invoice_number character varying(60),
    supplier_gstin character varying(20),
    supplier_state_code character varying(2),
    invoice_date date,
    received_on date,
    booking_id character varying(36),
    lead_id character varying(36),
    service_type character varying(20),
    reference_note character varying(255),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    fx_rate_source character varying(30),
    subtotal numeric(19,2) DEFAULT 0 NOT NULL,
    discount_total numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    gst_total numeric(19,2) DEFAULT 0 NOT NULL,
    cess_amount numeric(19,2) DEFAULT 0 NOT NULL,
    round_off numeric(19,2) DEFAULT 0 NOT NULL,
    grand_total numeric(19,2) DEFAULT 0 NOT NULL,
    itc_eligibility character varying(20) DEFAULT 'ELIGIBLE'::character varying NOT NULL,
    itc_note character varying(255),
    is_reverse_charge boolean DEFAULT false NOT NULL,
    tds_section character varying(20),
    tds_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    tds_amount numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_value_inr numeric(19,2) DEFAULT 0 NOT NULL,
    gst_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    grand_total_inr numeric(19,2) DEFAULT 0 NOT NULL,
    amount_paid numeric(19,2) DEFAULT 0 NOT NULL,
    credit_note_total numeric(19,2) DEFAULT 0 NOT NULL,
    balance_due numeric(19,2) DEFAULT 0 NOT NULL,
    balance_due_inr numeric(19,2) DEFAULT 0 NOT NULL,
    approved_at timestamp without time zone,
    approved_by character varying(36),
    cancelled_at timestamp without time zone,
    cancelled_by character varying(36),
    cancel_reason character varying(255),
    notes character varying(1000),
    file_key character varying(500),
    file_name character varying(255),
    content_type character varying(150),
    auto_drafted boolean DEFAULT false NOT NULL,
    branch_id character varying(36),
    deleted_at timestamp without time zone,
    commission numeric(19,2) DEFAULT 0 NOT NULL,
    supplier_tds numeric(19,2) DEFAULT 0 NOT NULL
);

CREATE TABLE supplier_invoice_line_item (
    id character varying(36) NOT NULL,
    supplier_invoice_id character varying(36) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    description character varying(500) NOT NULL,
    sac_code character varying(10),
    service_type character varying(30),
    quantity numeric(12,3) DEFAULT 1 NOT NULL,
    unit_price numeric(19,2) DEFAULT 0 NOT NULL,
    line_subtotal numeric(19,2) DEFAULT 0 NOT NULL,
    discount_amount numeric(19,2) DEFAULT 0 NOT NULL,
    taxable_value numeric(19,2) DEFAULT 0 NOT NULL,
    gst_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    cgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    sgst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    igst_amount numeric(19,2) DEFAULT 0 NOT NULL,
    line_total numeric(19,2) DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    deleted_at timestamp without time zone
);

CREATE TABLE supplier_ledger_entry (
    id character varying(36) NOT NULL,
    vendor_id character varying(36) NOT NULL,
    entry_date date NOT NULL,
    entry_type character varying(30) NOT NULL,
    source_type character varying(30) NOT NULL,
    source_id character varying(36) NOT NULL,
    document_number character varying(60),
    narration character varying(255) NOT NULL,
    booking_id character varying(36),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    debit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount numeric(19,2) DEFAULT 0 NOT NULL,
    debit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    credit_amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    reversed_entry_id character varying(36),
    created_at timestamp without time zone,
    created_by character varying(36)
);

CREATE TABLE supplier_payment (
    id character varying(36) NOT NULL,
    voucher_number character varying(40),
    financial_year character varying(9),
    direction character varying(20) NOT NULL,
    vendor_id character varying(36) NOT NULL,
    vendor_name character varying(150) NOT NULL,
    supplier_invoice_id character varying(36),
    supplier_credit_note_id character varying(36),
    booking_id character varying(36),
    currency_code character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    fx_rate_to_inr numeric(18,6) DEFAULT 1.000000 NOT NULL,
    amount numeric(19,2) DEFAULT 0 NOT NULL,
    amount_inr numeric(19,2) DEFAULT 0 NOT NULL,
    tds_withheld numeric(19,2) DEFAULT 0 NOT NULL,
    payment_mode character varying(20) NOT NULL,
    instrument_ref character varying(100),
    bank_account_label character varying(150),
    paid_on date NOT NULL,
    is_advance boolean DEFAULT false NOT NULL,
    applied_from_advance boolean DEFAULT false NOT NULL,
    reverses_payment_id character varying(36),
    reversed_at timestamp without time zone,
    reversed_by character varying(36),
    reversal_reason character varying(255),
    notes character varying(500),
    created_at timestamp without time zone,
    created_by character varying(36),
    branch_id character varying(36),
    settlement_fx_rate numeric(18,6),
    fx_variance_inr numeric(19,2),
    bank_account_id character varying(36)
);

CREATE TABLE tax_rate_config (
    id character varying(36) NOT NULL,
    tax_kind character varying(20) NOT NULL,
    label character varying(150) NOT NULL,
    sac_code character varying(10),
    supply_nature character varying(30) NOT NULL,
    rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    taxable_percent numeric(6,3) DEFAULT 100.000 NOT NULL,
    threshold_amount numeric(19,2),
    tcs_section character varying(20),
    effective_from date NOT NULL,
    effective_to date,
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

CREATE TABLE vendor (
    id character varying(36) NOT NULL,
    name character varying(150) NOT NULL,
    service_types text[] DEFAULT '{}'::text[] NOT NULL,
    contact_person character varying(150),
    phone character varying(20),
    email character varying(150),
    address character varying(500),
    gst_number character varying(15),
    notes character varying(1000),
    default_rate_note character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36),
    state_code character varying(2),
    pan_number character varying(10),
    is_prepaid boolean DEFAULT false NOT NULL,
    payment_terms_days integer,
    credit_limit_inr numeric(19,2),
    low_balance_threshold_inr numeric(19,2),
    tds_section character varying(20),
    tds_rate_percent numeric(6,3),
    bank_account_name character varying(150),
    bank_account_number character varying(34),
    bank_ifsc character varying(11),
    is_system boolean DEFAULT false NOT NULL
);

CREATE TABLE visa (
    id character varying(36) NOT NULL,
    client_id character varying(36) NOT NULL,
    client_name character varying(150) NOT NULL,
    agent_id character varying(36) NOT NULL,
    agent_name character varying(150) NOT NULL,
    lead_id character varying(36),
    country character varying(100) NOT NULL,
    visa_type character varying(50) NOT NULL,
    passport_number character varying(20),
    status character varying(30) NOT NULL,
    passport_collected boolean DEFAULT false NOT NULL,
    photos_collected boolean DEFAULT false NOT NULL,
    forms_filled boolean DEFAULT false NOT NULL,
    appointment_date date,
    biometrics_done boolean DEFAULT false NOT NULL,
    submitted_to_embassy boolean DEFAULT false NOT NULL,
    approved boolean DEFAULT false NOT NULL,
    rejected boolean DEFAULT false NOT NULL,
    passport_returned boolean DEFAULT false NOT NULL,
    visa_validity character varying(50),
    expiry_date date,
    application_date date,
    created_date timestamp without time zone,
    created_by character varying(36),
    updated_at timestamp without time zone,
    updated_by character varying(36)
);

ALTER TABLE ONLY accounting_change_alert
    ADD CONSTRAINT accounting_change_alert_pkey PRIMARY KEY (id);

ALTER TABLE ONLY agency_setting
    ADD CONSTRAINT agency_setting_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY bank_account
    ADD CONSTRAINT bank_account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY bank_match_rule
    ADD CONSTRAINT bank_match_rule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY bank_statement_import
    ADD CONSTRAINT bank_statement_import_pkey PRIMARY KEY (id);

ALTER TABLE ONLY bank_statement_profile
    ADD CONSTRAINT bank_statement_profile_pkey PRIMARY KEY (id);

ALTER TABLE ONLY bank_transaction
    ADD CONSTRAINT bank_transaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_cost_component
    ADD CONSTRAINT booking_cost_component_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_document
    ADD CONSTRAINT booking_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_passenger
    ADD CONSTRAINT booking_passenger_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking
    ADD CONSTRAINT booking_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking
    ADD CONSTRAINT booking_platform_booking_id_key UNIQUE (platform_booking_id);

ALTER TABLE ONLY booking_sector
    ADD CONSTRAINT booking_sector_pkey PRIMARY KEY (id);

ALTER TABLE ONLY branch
    ADD CONSTRAINT branch_pkey PRIMARY KEY (id);

ALTER TABLE ONLY client_invoice
    ADD CONSTRAINT client_invoice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY client
    ADD CONSTRAINT client_pkey PRIMARY KEY (id);

ALTER TABLE ONLY communication_log
    ADD CONSTRAINT communication_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY credit_note
    ADD CONSTRAINT credit_note_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer_ledger_entry
    ADD CONSTRAINT customer_ledger_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY document_number_sequence
    ADD CONSTRAINT document_number_sequence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice_line_item
    ADD CONSTRAINT invoice_line_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice
    ADD CONSTRAINT invoice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice_tax
    ADD CONSTRAINT invoice_tax_pkey PRIMARY KEY (id);

ALTER TABLE ONLY journal_entry
    ADD CONSTRAINT journal_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY journal_line
    ADD CONSTRAINT journal_line_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_follow_up
    ADD CONSTRAINT lead_follow_up_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_members
    ADD CONSTRAINT lead_members_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_notes
    ADD CONSTRAINT lead_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead
    ADD CONSTRAINT lead_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_proposal
    ADD CONSTRAINT lead_proposal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_service
    ADD CONSTRAINT lead_service_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_timeline
    ADD CONSTRAINT lead_timeline_pkey PRIMARY KEY (id);

ALTER TABLE ONLY lead_voucher
    ADD CONSTRAINT lead_voucher_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ledger_account
    ADD CONSTRAINT ledger_account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY markup_default
    ADD CONSTRAINT markup_default_pkey PRIMARY KEY (service_type);

ALTER TABLE ONLY member_documents
    ADD CONSTRAINT member_documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY member
    ADD CONSTRAINT member_pkey PRIMARY KEY (member_id);

ALTER TABLE ONLY payment_receipt
    ADD CONSTRAINT payment_receipt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shift_handover
    ADD CONSTRAINT shift_handover_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_credential
    ADD CONSTRAINT supplier_credential_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_credit_note
    ADD CONSTRAINT supplier_credit_note_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_invoice_line_item
    ADD CONSTRAINT supplier_invoice_line_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_invoice
    ADD CONSTRAINT supplier_invoice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_ledger_entry
    ADD CONSTRAINT supplier_ledger_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_payment
    ADD CONSTRAINT supplier_payment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tax_rate_config
    ADD CONSTRAINT tax_rate_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking
    ADD CONSTRAINT uq_booking_booking_number UNIQUE (booking_number);

ALTER TABLE ONLY vendor
    ADD CONSTRAINT vendor_pkey PRIMARY KEY (id);

ALTER TABLE ONLY visa
    ADD CONSTRAINT visa_pkey PRIMARY KEY (id);

CREATE INDEX idx_accounting_change_alert_booking ON accounting_change_alert USING btree (booking_id);

CREATE INDEX idx_accounting_change_alert_unacked ON accounting_change_alert USING btree (acknowledged, raised_at);

CREATE INDEX idx_agency_setting_kind ON agency_setting USING btree (kind, is_active);

CREATE INDEX idx_audit_log_actor ON audit_log USING btree (actor_id, created_at DESC);

CREATE INDEX idx_audit_log_created ON audit_log USING btree (created_at DESC);

CREATE INDEX idx_audit_log_record ON audit_log USING btree (entity_type, entity_id, created_at DESC);

CREATE INDEX idx_audit_log_type ON audit_log USING btree (entity_type, created_at DESC);

CREATE INDEX idx_bank_match_rule_priority ON bank_match_rule USING btree (priority);

CREATE INDEX idx_bank_statement_import_account ON bank_statement_import USING btree (bank_account_id);

CREATE INDEX idx_bank_transaction_account_status ON bank_transaction USING btree (bank_account_id, match_status);

CREATE INDEX idx_bank_transaction_import ON bank_transaction USING btree (import_id);

CREATE INDEX idx_booking_agent ON booking USING btree (agent_id);

CREATE INDEX idx_booking_client ON booking USING btree (client_id);

CREATE INDEX idx_booking_cost_component_booking ON booking_cost_component USING btree (booking_id);

CREATE INDEX idx_booking_cost_component_vendor ON booking_cost_component USING btree (vendor_id);

CREATE INDEX idx_booking_departure_date ON booking USING btree (departure_date);

CREATE INDEX idx_booking_document_booking ON booking_document USING btree (booking_id, sort_order);

CREATE INDEX idx_booking_lead ON booking USING btree (lead_id);

CREATE INDEX idx_booking_passenger_booking ON booking_passenger USING btree (booking_id);

CREATE INDEX idx_booking_payment_source ON booking USING btree (payment_status_source);

CREATE INDEX idx_booking_refund_state ON booking USING btree (refund_state) WHERE ((refund_state)::text <> 'NOT_APPLICABLE'::text);

CREATE INDEX idx_booking_scope ON booking USING btree (service_type, service_agent_id);

CREATE INDEX idx_booking_sector_passenger ON booking_sector USING btree (booking_passenger_id);

CREATE INDEX idx_booking_service ON booking USING btree (service_id);

CREATE INDEX idx_booking_status ON booking USING btree (booking_status);

CREATE INDEX idx_booking_type ON booking USING btree (type);

CREATE UNIQUE INDEX idx_client_identifier ON client USING btree (identifier) WHERE is_active;

CREATE INDEX idx_client_invoice_agent ON client_invoice USING btree (agent_id);

CREATE INDEX idx_client_invoice_client ON client_invoice USING btree (client_id);

CREATE INDEX idx_client_invoice_due_date ON client_invoice USING btree (due_date);

CREATE INDEX idx_client_invoice_lead ON client_invoice USING btree (lead_id);

CREATE INDEX idx_client_invoice_status ON client_invoice USING btree (status);

CREATE INDEX idx_client_name_trgm ON client USING gin (name public.gin_trgm_ops);

CREATE INDEX idx_client_type ON client USING btree (type);

CREATE INDEX idx_cn_client ON credit_note USING btree (client_id);

CREATE INDEX idx_cn_invoice ON credit_note USING btree (invoice_id);

CREATE UNIQUE INDEX idx_cn_number ON credit_note USING btree (credit_note_number) WHERE (credit_note_number IS NOT NULL);

CREATE INDEX idx_communication_log_client ON communication_log USING btree (client_id, occurred_at DESC);

CREATE INDEX idx_communication_log_lead ON communication_log USING btree (lead_id, occurred_at DESC);

CREATE INDEX idx_credit_note_branch ON credit_note USING btree (branch_id);

CREATE UNIQUE INDEX idx_doc_number_seq_key ON document_number_sequence USING btree (document_kind, financial_year);

CREATE UNIQUE INDEX idx_feedback_booking ON feedback USING btree (booking_id);

CREATE INDEX idx_feedback_client ON feedback USING btree (client_id);

CREATE INDEX idx_invoice_agent ON invoice USING btree (agent_id);

CREATE INDEX idx_invoice_booking ON invoice USING btree (booking_id);

CREATE INDEX idx_invoice_branch ON invoice USING btree (branch_id);

CREATE INDEX idx_invoice_client ON invoice USING btree (client_id);

CREATE INDEX idx_invoice_date ON invoice USING btree (invoice_date);

CREATE INDEX idx_invoice_line_booking ON invoice_line_item USING btree (booking_id) WHERE (deleted_at IS NULL);

CREATE INDEX idx_invoice_line_invoice ON invoice_line_item USING btree (invoice_id, sort_order);

CREATE INDEX idx_invoice_line_sac ON invoice_line_item USING btree (sac_code, gst_rate_percent);

CREATE INDEX idx_invoice_line_voucher ON invoice_line_item USING btree (booking_document_id) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX idx_invoice_number ON invoice USING btree (invoice_number) WHERE (invoice_number IS NOT NULL);

CREATE INDEX idx_invoice_status ON invoice USING btree (status);

CREATE INDEX idx_invoice_tax_invoice ON invoice_tax USING btree (invoice_id, sort_order);

CREATE INDEX idx_journal_entry_booking ON journal_entry USING btree (booking_id);

CREATE INDEX idx_journal_entry_date ON journal_entry USING btree (entry_date);

CREATE INDEX idx_journal_entry_source ON journal_entry USING btree (source_type, source_id);

CREATE INDEX idx_journal_line_account ON journal_line USING btree (account_code);

CREATE INDEX idx_journal_line_entry ON journal_line USING btree (journal_entry_id);

CREATE INDEX idx_journal_line_party ON journal_line USING btree (party_type, party_id);

CREATE INDEX idx_lead_client ON lead USING btree (client_id);

CREATE INDEX idx_lead_created_by ON lead USING btree (created_by);

CREATE INDEX idx_lead_escalated ON lead USING btree (escalated) WHERE escalated;

CREATE INDEX idx_lead_follow_up_agent ON lead_follow_up USING btree (assigned_agent_id, status, due_date);

CREATE INDEX idx_lead_follow_up_date ON lead USING btree (follow_up_date);

CREATE INDEX idx_lead_follow_up_lead ON lead_follow_up USING btree (lead_id, status, due_date);

CREATE INDEX idx_lead_members_lead ON lead_members USING btree (lead_id);

CREATE INDEX idx_lead_members_member ON lead_members USING btree (member_id);

CREATE UNIQUE INDEX idx_lead_members_pair ON lead_members USING btree (lead_id, member_id);

CREATE INDEX idx_lead_notes_lead ON lead_notes USING btree (lead_id);

CREATE INDEX idx_lead_proposal_lead ON lead_proposal USING btree (lead_id);

CREATE INDEX idx_lead_proposal_option_group ON lead_proposal USING btree (lead_id, option_group);

CREATE INDEX idx_lead_proposal_service ON lead_proposal USING btree (service_id);

CREATE UNIQUE INDEX idx_lead_public_proposal_token ON lead USING btree (public_proposal_token) WHERE (public_proposal_token IS NOT NULL);

CREATE INDEX idx_lead_service_agent ON lead_service USING btree (assigned_agent_id);

CREATE INDEX idx_lead_service_dates ON lead_service USING btree (date_from);

CREATE INDEX idx_lead_service_lead ON lead_service USING btree (lead_id, sort_order);

CREATE INDEX idx_lead_service_type_status ON lead_service USING btree (type, status);

CREATE INDEX idx_lead_status ON lead USING btree (current_status);

CREATE INDEX idx_lead_timeline_lead ON lead_timeline USING btree (lead_id, created_at DESC);

CREATE INDEX idx_lead_timeline_service ON lead_timeline USING btree (service_id, created_at DESC);

CREATE INDEX idx_lead_voucher_lead ON lead_voucher USING btree (lead_id);

CREATE INDEX idx_lead_voucher_service ON lead_voucher USING btree (service_id);

CREATE INDEX idx_ledger_client ON customer_ledger_entry USING btree (client_id, entry_date, created_at);

CREATE UNIQUE INDEX idx_ledger_source ON customer_ledger_entry USING btree (source_type, source_id, entry_type);

CREATE INDEX idx_member_client ON member USING btree (client_id);

CREATE INDEX idx_member_documents_member ON member_documents USING btree (member_id);

CREATE INDEX idx_member_phone ON member USING btree (phone);

CREATE UNIQUE INDEX idx_member_primary ON member USING btree (client_id) WHERE (((type)::text = 'CLIENT'::text) AND is_active);

CREATE INDEX idx_payment_receipt_bank_account ON payment_receipt USING btree (bank_account_id);

CREATE INDEX idx_payment_receipt_branch ON payment_receipt USING btree (branch_id);

CREATE INDEX idx_receipt_booking ON payment_receipt USING btree (booking_id);

CREATE INDEX idx_receipt_client ON payment_receipt USING btree (client_id, received_on DESC);

CREATE INDEX idx_receipt_cn ON payment_receipt USING btree (credit_note_id);

CREATE INDEX idx_receipt_invoice ON payment_receipt USING btree (invoice_id);

CREATE UNIQUE INDEX idx_receipt_number ON payment_receipt USING btree (receipt_number) WHERE (receipt_number IS NOT NULL);

CREATE INDEX idx_shift_handover_to_agent ON shift_handover USING btree (to_agent_id, shift_ended_at DESC);

CREATE INDEX idx_shift_handover_unacknowledged ON shift_handover USING btree (acknowledged_at) WHERE (acknowledged_at IS NULL);

CREATE INDEX idx_sup_cn_invoice ON supplier_credit_note USING btree (supplier_invoice_id);

CREATE INDEX idx_sup_cn_vendor ON supplier_credit_note USING btree (vendor_id);

CREATE INDEX idx_sup_inv_booking ON supplier_invoice USING btree (booking_id);

CREATE INDEX idx_sup_inv_due ON supplier_invoice USING btree (due_date) WHERE ((status)::text = ANY ((ARRAY['APPROVED'::character varying, 'PARTIALLY_PAID'::character varying])::text[]));

CREATE INDEX idx_sup_inv_line ON supplier_invoice_line_item USING btree (supplier_invoice_id, sort_order);

CREATE INDEX idx_sup_inv_vendor ON supplier_invoice USING btree (vendor_id, invoice_date DESC);

CREATE UNIQUE INDEX idx_sup_ledger_source ON supplier_ledger_entry USING btree (source_type, source_id, entry_type);

CREATE INDEX idx_sup_ledger_vendor ON supplier_ledger_entry USING btree (vendor_id, entry_date, created_at);

CREATE INDEX idx_sup_pay_booking ON supplier_payment USING btree (booking_id);

CREATE INDEX idx_sup_pay_invoice ON supplier_payment USING btree (supplier_invoice_id);

CREATE UNIQUE INDEX idx_sup_pay_number ON supplier_payment USING btree (voucher_number) WHERE (voucher_number IS NOT NULL);

CREATE INDEX idx_sup_pay_vendor ON supplier_payment USING btree (vendor_id, paid_on DESC);

CREATE UNIQUE INDEX idx_supplier_credential_provider ON supplier_credential USING btree (provider);

CREATE INDEX idx_supplier_invoice_branch ON supplier_invoice USING btree (branch_id);

CREATE INDEX idx_supplier_invoice_status ON supplier_invoice USING btree (status);

CREATE INDEX idx_supplier_payment_bank_account ON supplier_payment USING btree (bank_account_id);

CREATE INDEX idx_supplier_payment_branch ON supplier_payment USING btree (branch_id);

CREATE INDEX idx_tax_rate_config_lookup ON tax_rate_config USING btree (tax_kind, supply_nature, effective_from DESC);

CREATE UNIQUE INDEX idx_tax_rate_default ON tax_rate_config USING btree (tax_kind, supply_nature) WHERE (is_default AND is_active);

CREATE INDEX idx_vendor_active_sort ON vendor USING btree (is_active, sort_order, name);

CREATE INDEX idx_vendor_name ON vendor USING btree (lower((name)::text));

CREATE INDEX idx_vendor_service_types ON vendor USING gin (service_types);

CREATE INDEX idx_visa_agent ON visa USING btree (agent_id);

CREATE INDEX idx_visa_client ON visa USING btree (client_id);

CREATE INDEX idx_visa_status ON visa USING btree (status);

CREATE UNIQUE INDEX uq_bank_transaction_dup ON bank_transaction USING btree (bank_account_id, txn_date, amount, bank_reference);

CREATE UNIQUE INDEX uq_journal_entry_source ON journal_entry USING btree (source_type, source_id, purpose) WHERE (source_id IS NOT NULL);

CREATE UNIQUE INDEX uq_ledger_account_code ON ledger_account USING btree (code);

CREATE UNIQUE INDEX uq_sup_inv_number ON supplier_invoice USING btree (vendor_id, lower((supplier_invoice_number)::text)) WHERE ((vendor_id IS NOT NULL) AND (supplier_invoice_number IS NOT NULL) AND ((status)::text <> 'CANCELLED'::text) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_vendor_name_active ON vendor USING btree (lower((name)::text)) WHERE is_active;

CREATE UNIQUE INDEX uq_vendor_system ON vendor USING btree (is_system) WHERE is_system;

-- ---------------------------------------------------------------------------
-- Seed rows (the rows the old chain inserted)
-- ---------------------------------------------------------------------------

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

-- The vendor every Voyra platform booking's CRM mirror uses; system-managed, cannot be edited or deactivated.
INSERT INTO vendor (id, name, service_types, is_active, is_system, sort_order, created_at)
VALUES ('voyra-platform-' || substr(md5(random()::text), 1, 20), 'Voyra Platform', ARRAY['FLIGHT','HOTEL'], TRUE, TRUE, 0, now());

-- 1310 sits under 1300 Supplier Advances (same code as SystemAccount.VOYRA_WALLET_ADVANCE).
INSERT INTO ledger_account (id, code, name, account_type, parent_code, is_system, is_control, is_active, created_date)
VALUES ('voyra-wallet-' || substr(md5(random()::text), 1, 22), '1310', 'Voyra Wallet (advance to supplier)',
        'ASSET', '1300', TRUE, FALSE, TRUE, now());
