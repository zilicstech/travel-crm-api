-- Per-Agency schema. agent_id/client_id/member_id/lead_id are plain indexed VARCHAR columns
-- (no cross-schema FK - the schema boundary itself is the tenant isolation mechanism).
-- Snapshot *_name columns are denormalized on purpose (see backend plan's "Denormalization
-- strategy") so every list screen is a single flat SELECT with zero joins; they are kept
-- live-synced by a bulk UPDATE in the same transaction whenever the source name changes.
--
-- V1 RESET: this file was rewritten in place (and the old V2 deleted) when the Client/Member
-- party model replaced the flat customer/family_member model. That is normally forbidden by
-- blueprint 2.5 "never edit an applied migration" - it was allowed exactly once because
-- nothing had been deployed anywhere and the only existing schemas were throwaway local ones.
-- From here on the rule holds again: add V2, V3, ... and never touch this file.
--
-- The data model is a three-level spine:
--     client  --1:N-->  member  --M:N via lead_members-->  lead
-- `client` is the commercial entity (a B2C household or a B2B group/company). `member` is a
-- person, including the client themself (type = CLIENT). `lead_members` is the per-lead
-- traveller manifest, and is a real entity rather than a bare link table so that a group
-- member dropping out after documents were collected is recorded rather than deleted.

-- ---------------------------------------------------------------------------
-- CLIENT - the commercial entity the agency deals with
-- ---------------------------------------------------------------------------
CREATE TABLE client (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    -- Phone for B2C, group/company handle for B2B. Deduplication key.
    identifier    VARCHAR(150) NOT NULL,
    name          VARCHAR(150) NOT NULL,
    type          VARCHAR(10) NOT NULL,
    -- Owning agent. Agent-scoped visibility across every module filters on this.
    agent_id      VARCHAR(36) NOT NULL,
    agent_name    VARCHAR(150) NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP,
    created_by    VARCHAR(36),
    modified_at   TIMESTAMP,
    modified_by   VARCHAR(36)
);

CREATE INDEX idx_client_agent ON client (agent_id);
CREATE INDEX idx_client_type ON client (type);
-- Duplicate-client detection (BRD loophole 9): one live client per identifier.
CREATE UNIQUE INDEX idx_client_identifier ON client (identifier) WHERE is_active;

-- ---------------------------------------------------------------------------
-- MEMBER - a person. Every client has exactly one member of type CLIENT
-- (themself); everyone else on the roster is type MEMBER.
--
-- Deliberately NO age column: pax type is a function of dob and the *travel*
-- date, so a stored age silently mis-fares a child who turns 12 between
-- enquiry and departure. See util/PaxTypeCalculator.
--
-- Identity fields are nullable so an agent can quick-add a traveller knowing
-- only a name; completeness is gated at proposal/booking time instead. Making
-- them NOT NULL just produces "Guest 1", "Guest 2" placeholder rows.
-- ---------------------------------------------------------------------------
CREATE TABLE member (
    member_id       VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id       VARCHAR(36) NOT NULL,
    name            VARCHAR(150) NOT NULL,
    email           VARCHAR(150),
    country_code    VARCHAR(6),
    phone           VARCHAR(20),
    type            VARCHAR(10) NOT NULL,
    dob             DATE,
    gender          VARCHAR(30),
    relation        VARCHAR(20) NOT NULL,
    nationality     VARCHAR(100),
    passport_number VARCHAR(20),
    passport_expiry DATE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP,
    created_by      VARCHAR(36),
    modified_at     TIMESTAMP,
    modified_by     VARCHAR(36)
);

CREATE INDEX idx_member_client ON member (client_id);
CREATE INDEX idx_member_phone ON member (phone);
-- Exactly one primary member (the client themself) per client.
CREATE UNIQUE INDEX idx_member_primary ON member (client_id) WHERE type = 'CLIENT' AND is_active;

-- ---------------------------------------------------------------------------
-- MEMBER_DOCUMENTS - passport/visa/aadhaar scans. file_key is opaque and comes
-- from FileStorageService; no filesystem path is ever baked into business logic.
-- ---------------------------------------------------------------------------
CREATE TABLE member_documents (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    member_id   VARCHAR(36) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    file_key    VARCHAR(500) NOT NULL,
    doc_type    VARCHAR(50),
    uploaded_at TIMESTAMP,
    uploaded_by VARCHAR(36)
);

CREATE INDEX idx_member_documents_member ON member_documents (member_id);

-- ---------------------------------------------------------------------------
-- LEAD - an enquiry belonging to one client.
--
-- Contact details are NOT duplicated here; they resolve through client -> the
-- primary member. kid_ages holds each child's age as quoted by the client
-- (the whiteboard's "config: 1st kid = 3 years, 2nd kid = 5 years"); the
-- infant count is derived from it rather than stored. total_travellers is
-- stored for list-query performance but recomputed from adults + kids on
-- every write, the same way booking.profit is.
-- ---------------------------------------------------------------------------
CREATE TABLE lead (
    id                     VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id              VARCHAR(36) NOT NULL,
    client_name            VARCHAR(150) NOT NULL,
    client_type            VARCHAR(10) NOT NULL,
    destination            VARCHAR(150) NOT NULL,
    travel_date_from       DATE,
    travel_date_to         DATE,
    adults                 INTEGER NOT NULL DEFAULT 1,
    kids                   INTEGER NOT NULL DEFAULT 0,
    kid_ages               INTEGER[] NOT NULL DEFAULT '{}',
    total_travellers       INTEGER NOT NULL DEFAULT 1,
    lead_description       TEXT,
    preferences            TEXT,
    current_status         VARCHAR(20) NOT NULL,
    source                 VARCHAR(20) NOT NULL,
    priority               VARCHAR(10) NOT NULL,
    categories             TEXT[] NOT NULL DEFAULT '{}',
    budget                 VARCHAR(50),
    assigned_to            VARCHAR(36) NOT NULL,
    assigned_agent_name    VARCHAR(150) NOT NULL,
    follow_up_date         DATE,
    lost_reason            VARCHAR(255),
    public_proposal_token  VARCHAR(32),
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP,
    created_by             VARCHAR(36),
    updated_at             TIMESTAMP,
    updated_by             VARCHAR(36)
);

CREATE INDEX idx_lead_assigned_to ON lead (assigned_to);
CREATE INDEX idx_lead_client ON lead (client_id);
CREATE INDEX idx_lead_status ON lead (current_status);
CREATE INDEX idx_lead_follow_up_date ON lead (follow_up_date);
CREATE UNIQUE INDEX idx_lead_public_proposal_token ON lead (public_proposal_token) WHERE public_proposal_token IS NOT NULL;

-- ---------------------------------------------------------------------------
-- LEAD_MEMBERS - the traveller manifest for one lead.
--
-- A real entity, not a bare (lead_id, member_id) link: a group member dropping
-- out after their visa was filed is the single most common event in group
-- travel, and deleting the row would destroy that history. status +
-- dropped_reason keep it. The document checklist lives here per traveller
-- because one checklist for a 14-person lead is meaningless.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_members (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id              VARCHAR(36) NOT NULL,
    member_id            VARCHAR(36) NOT NULL,
    client_id            VARCHAR(36) NOT NULL,
    member_name          VARCHAR(150) NOT NULL,
    status               VARCHAR(15) NOT NULL,
    passport_collected   BOOLEAN NOT NULL DEFAULT FALSE,
    photos_collected     BOOLEAN NOT NULL DEFAULT FALSE,
    forms_filled         BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_to_embassy BOOLEAN NOT NULL DEFAULT FALSE,
    visa_approved        BOOLEAN NOT NULL DEFAULT FALSE,
    dropped_reason       VARCHAR(255),
    created_at           TIMESTAMP,
    created_by           VARCHAR(36),
    modified_at          TIMESTAMP,
    modified_by          VARCHAR(36)
);

CREATE UNIQUE INDEX idx_lead_members_pair ON lead_members (lead_id, member_id);
CREATE INDEX idx_lead_members_lead ON lead_members (lead_id);
CREATE INDEX idx_lead_members_member ON lead_members (member_id);

-- ---------------------------------------------------------------------------
-- LEAD_PROPOSAL - quotation line items for a lead. net_cost never leaves the
-- server on the public proposal surface; margin is always recomputed.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_proposal (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id       VARCHAR(36) NOT NULL,
    type          VARCHAR(20) NOT NULL,
    description   VARCHAR(255) NOT NULL,
    supplier      VARCHAR(150),
    net_cost      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    selling_price NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMP,
    created_by    VARCHAR(36)
);

CREATE INDEX idx_lead_proposal_lead ON lead_proposal (lead_id);

-- ---------------------------------------------------------------------------
-- LEAD_NOTES - agent-authored interaction log. Free text, written by a human.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_notes (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id         VARCHAR(36) NOT NULL,
    author_agent_id VARCHAR(36) NOT NULL,
    author_name     VARCHAR(150) NOT NULL,
    text            TEXT NOT NULL,
    created_at      TIMESTAMP
);

CREATE INDEX idx_lead_notes_lead ON lead_notes (lead_id);

-- ---------------------------------------------------------------------------
-- LEAD_TIMELINE - system-written activity stream (BRD "Lead Timeline").
-- Distinct from lead_notes: rows here are emitted by the service on every
-- state change, are append-only, and have no client-facing write endpoint.
-- Powers both the list view and the status-grouped kanban view.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_timeline (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id        VARCHAR(36) NOT NULL,
    event_type     VARCHAR(30) NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20),
    actor_agent_id VARCHAR(36) NOT NULL,
    actor_name     VARCHAR(150) NOT NULL,
    description    VARCHAR(500) NOT NULL,
    created_at     TIMESTAMP
);

CREATE INDEX idx_lead_timeline_lead ON lead_timeline (lead_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- VISA - standalone visa case, owned by a client.
--
-- Known limitation: one case per client, so a family visa application is not
-- yet expressible as a single case. Deliberately out of scope for this change;
-- moving it to member_id is the follow-up.
-- ---------------------------------------------------------------------------
CREATE TABLE visa (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id            VARCHAR(36) NOT NULL,
    client_name          VARCHAR(150) NOT NULL,
    agent_id             VARCHAR(36) NOT NULL,
    agent_name           VARCHAR(150) NOT NULL,
    lead_id              VARCHAR(36),
    country              VARCHAR(100) NOT NULL,
    visa_type            VARCHAR(50) NOT NULL,
    passport_number      VARCHAR(20),
    status               VARCHAR(30) NOT NULL,
    passport_collected   BOOLEAN NOT NULL DEFAULT FALSE,
    photos_collected     BOOLEAN NOT NULL DEFAULT FALSE,
    forms_filled         BOOLEAN NOT NULL DEFAULT FALSE,
    appointment_date     DATE,
    biometrics_done      BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_to_embassy BOOLEAN NOT NULL DEFAULT FALSE,
    approved             BOOLEAN NOT NULL DEFAULT FALSE,
    rejected             BOOLEAN NOT NULL DEFAULT FALSE,
    passport_returned    BOOLEAN NOT NULL DEFAULT FALSE,
    visa_validity        VARCHAR(50),
    expiry_date          DATE,
    application_date     DATE,
    created_date         TIMESTAMP
);

CREATE INDEX idx_visa_client ON visa (client_id);
CREATE INDEX idx_visa_agent ON visa (agent_id);
CREATE INDEX idx_visa_status ON visa (status);

-- ---------------------------------------------------------------------------
-- BOOKING - profit is stored for query performance but always recomputed as
-- selling_price - net_cost on write; a stale stored value is never trusted.
--
-- Known limitation: single-traveller, and no lead_id. A multi-pax group lead
-- cannot convert to one booking yet. Deliberately out of scope for this change.
-- ---------------------------------------------------------------------------
CREATE TABLE booking (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id      VARCHAR(36) NOT NULL,
    client_name    VARCHAR(150) NOT NULL,
    agent_id       VARCHAR(36) NOT NULL,
    agent_name     VARCHAR(150) NOT NULL,
    type           VARCHAR(20) NOT NULL,
    destination    VARCHAR(150) NOT NULL,
    pnr            VARCHAR(20),
    ticket_no      VARCHAR(50),
    airline        VARCHAR(100),
    supplier       VARCHAR(150),
    journey_date   DATE,
    return_date    DATE,
    trip_type      VARCHAR(20),
    net_cost       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    selling_price  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    profit         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    booking_status VARCHAR(20) NOT NULL,
    payment_status VARCHAR(20) NOT NULL,
    booking_date   DATE,
    cancel_reason  VARCHAR(255),
    refund_status  VARCHAR(100),
    created_date   TIMESTAMP
);

CREATE INDEX idx_booking_client ON booking (client_id);
CREATE INDEX idx_booking_agent ON booking (agent_id);
CREATE INDEX idx_booking_type ON booking (type);
CREATE INDEX idx_booking_status ON booking (booking_status);

-- ---------------------------------------------------------------------------
-- CLIENT_INVOICE - GST and payment status are always server-derived.
-- amount_paid is cumulative ("total paid to date"), never a single receipt.
-- ---------------------------------------------------------------------------
CREATE TABLE client_invoice (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id      VARCHAR(36) NOT NULL,
    client_name    VARCHAR(150) NOT NULL,
    agent_id       VARCHAR(36) NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    gst            NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_with_gst NUMERIC(19, 2) NOT NULL DEFAULT 0,
    amount_paid    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,
    invoice_date   DATE,
    due_date       DATE,
    payment_mode   VARCHAR(50),
    created_date   TIMESTAMP
);

CREATE INDEX idx_client_invoice_client ON client_invoice (client_id);
CREATE INDEX idx_client_invoice_agent ON client_invoice (agent_id);
CREATE INDEX idx_client_invoice_status ON client_invoice (status);
CREATE INDEX idx_client_invoice_due_date ON client_invoice (due_date);

-- ---------------------------------------------------------------------------
-- SUPPLIER_INVOICE - accounts payable. Owner-only; never agent-scoped.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_invoice (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    supplier_name VARCHAR(150) NOT NULL,
    category      VARCHAR(20) NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status        VARCHAR(20) NOT NULL,
    due_date      DATE,
    booking_ref   VARCHAR(36),
    created_date  TIMESTAMP
);

CREATE INDEX idx_supplier_invoice_status ON supplier_invoice (status);
CREATE INDEX idx_supplier_invoice_booking_ref ON supplier_invoice (booking_ref);
