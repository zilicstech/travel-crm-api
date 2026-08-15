-- Per-Agency schema. agent_id/customer_id/lead_id are plain indexed VARCHAR columns
-- (no cross-schema FK - the schema boundary itself is the tenant isolation mechanism).
-- Snapshot *_name columns are denormalized on purpose (see backend plan's "Denormalization
-- strategy") so every list screen is a single flat SELECT with zero joins; they are kept
-- live-synced by a bulk UPDATE in the same transaction whenever the source name changes.

CREATE TABLE customer (
    id                 VARCHAR(36) NOT NULL PRIMARY KEY,
    agent_id           VARCHAR(36) NOT NULL,
    agent_name         VARCHAR(150) NOT NULL,
    name               VARCHAR(150) NOT NULL,
    email              VARCHAR(150),
    country_code       VARCHAR(6),
    phone              VARCHAR(20),
    dob                DATE,
    gender             VARCHAR(30),
    city               VARCHAR(100),
    country            VARCHAR(100),
    nationality        VARCHAR(100),
    passport_number    VARCHAR(20),
    passport_expiry    DATE,
    preferred_airline  VARCHAR(100),
    preferred_cabin    VARCHAR(30),
    status             VARCHAR(20) NOT NULL,
    tags               TEXT[] NOT NULL DEFAULT '{}',
    created_date       TIMESTAMP
);

CREATE INDEX idx_customer_agent ON customer (agent_id);
CREATE INDEX idx_customer_status ON customer (status);
CREATE INDEX idx_customer_phone ON customer (phone);

CREATE TABLE customer_document (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id   VARCHAR(36) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    file_key      VARCHAR(500) NOT NULL,
    doc_type      VARCHAR(50),
    uploaded_date TIMESTAMP
);

CREATE INDEX idx_customer_document_customer ON customer_document (customer_id);

CREATE TABLE family_member (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    name        VARCHAR(150) NOT NULL,
    relation    VARCHAR(20) NOT NULL,
    dob         DATE
);

CREATE INDEX idx_family_member_customer ON family_member (customer_id);

CREATE TABLE family_member_document (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    family_member_id VARCHAR(36) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    file_key         VARCHAR(500) NOT NULL,
    doc_type         VARCHAR(50),
    uploaded_date    TIMESTAMP
);

CREATE INDEX idx_family_member_document_member ON family_member_document (family_member_id);

CREATE TABLE customer_interaction (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(36) NOT NULL,
    author_agent_id VARCHAR(36) NOT NULL,
    author_name     VARCHAR(150) NOT NULL,
    note            TEXT NOT NULL,
    created_date    TIMESTAMP
);

CREATE INDEX idx_customer_interaction_customer ON customer_interaction (customer_id);

CREATE TABLE lead (
    id                     VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id            VARCHAR(36),
    name                   VARCHAR(150) NOT NULL,
    email                  VARCHAR(150),
    country_code           VARCHAR(6),
    phone                  VARCHAR(20) NOT NULL,
    destination            VARCHAR(150) NOT NULL,
    travel_date_from       DATE,
    travel_date_to         DATE,
    budget                 VARCHAR(50),
    status                 VARCHAR(20) NOT NULL,
    source                 VARCHAR(20) NOT NULL,
    priority               VARCHAR(10) NOT NULL,
    categories             TEXT[] NOT NULL DEFAULT '{}',
    assigned_to            VARCHAR(36) NOT NULL,
    assigned_agent_name    VARCHAR(150) NOT NULL,
    follow_up_date         DATE,
    lost_reason            VARCHAR(255),
    adults                 INTEGER NOT NULL DEFAULT 1,
    children               INTEGER NOT NULL DEFAULT 0,
    infants                INTEGER NOT NULL DEFAULT 0,
    special_requirements   TEXT,
    -- Visa tracker: nullable, only meaningful when 'VISA' = ANY(categories).
    passport_collected     BOOLEAN,
    photos_collected       BOOLEAN,
    forms_filled           BOOLEAN,
    submitted_to_embassy   BOOLEAN,
    approved               BOOLEAN,
    public_proposal_token  VARCHAR(32),
    created_date           TIMESTAMP
);

CREATE INDEX idx_lead_assigned_to ON lead (assigned_to);
CREATE INDEX idx_lead_customer ON lead (customer_id);
CREATE INDEX idx_lead_status ON lead (status);
CREATE INDEX idx_lead_follow_up_date ON lead (follow_up_date);
CREATE UNIQUE INDEX idx_lead_public_proposal_token ON lead (public_proposal_token) WHERE public_proposal_token IS NOT NULL;

CREATE TABLE proposal_item (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id       VARCHAR(36) NOT NULL,
    type          VARCHAR(20) NOT NULL,
    description   VARCHAR(255) NOT NULL,
    supplier      VARCHAR(150),
    net_cost      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    selling_price NUMERIC(19, 2) NOT NULL DEFAULT 0
);

CREATE INDEX idx_proposal_item_lead ON proposal_item (lead_id);

CREATE TABLE lead_note (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id         VARCHAR(36) NOT NULL,
    author_agent_id VARCHAR(36) NOT NULL,
    author_name     VARCHAR(150) NOT NULL,
    text            TEXT NOT NULL,
    created_date    TIMESTAMP
);

CREATE INDEX idx_lead_note_lead ON lead_note (lead_id);

CREATE TABLE visa (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id         VARCHAR(36) NOT NULL,
    customer_name       VARCHAR(150) NOT NULL,
    agent_id            VARCHAR(36) NOT NULL,
    agent_name          VARCHAR(150) NOT NULL,
    lead_id             VARCHAR(36),
    country             VARCHAR(100) NOT NULL,
    visa_type           VARCHAR(50) NOT NULL,
    passport_number     VARCHAR(20),
    status              VARCHAR(30) NOT NULL,
    passport_collected  BOOLEAN NOT NULL DEFAULT FALSE,
    photos_collected    BOOLEAN NOT NULL DEFAULT FALSE,
    forms_filled        BOOLEAN NOT NULL DEFAULT FALSE,
    appointment_date    DATE,
    biometrics_done     BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_to_embassy BOOLEAN NOT NULL DEFAULT FALSE,
    approved            BOOLEAN NOT NULL DEFAULT FALSE,
    rejected            BOOLEAN NOT NULL DEFAULT FALSE,
    passport_returned   BOOLEAN NOT NULL DEFAULT FALSE,
    visa_validity       VARCHAR(50),
    expiry_date         DATE,
    application_date    DATE,
    created_date        TIMESTAMP
);

CREATE INDEX idx_visa_customer ON visa (customer_id);
CREATE INDEX idx_visa_agent ON visa (agent_id);
CREATE INDEX idx_visa_status ON visa (status);

CREATE TABLE booking (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id    VARCHAR(36) NOT NULL,
    customer_name  VARCHAR(150) NOT NULL,
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

CREATE INDEX idx_booking_customer ON booking (customer_id);
CREATE INDEX idx_booking_agent ON booking (agent_id);
CREATE INDEX idx_booking_type ON booking (type);
CREATE INDEX idx_booking_status ON booking (booking_status);

CREATE TABLE client_invoice (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(36) NOT NULL,
    customer_name   VARCHAR(150) NOT NULL,
    agent_id        VARCHAR(36) NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    gst             NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_with_gst  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    amount_paid     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL,
    invoice_date    DATE,
    due_date        DATE,
    payment_mode    VARCHAR(50),
    created_date    TIMESTAMP
);

CREATE INDEX idx_client_invoice_customer ON client_invoice (customer_id);
CREATE INDEX idx_client_invoice_agent ON client_invoice (agent_id);
CREATE INDEX idx_client_invoice_status ON client_invoice (status);
CREATE INDEX idx_client_invoice_due_date ON client_invoice (due_date);

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
