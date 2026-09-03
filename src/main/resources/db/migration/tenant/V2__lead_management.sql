-- Lead management spine, per docs/LLD_LEAD_MANAGEMENT.md. This is the one migration that
-- lands the entire §13 delta in one shot: lead_service / lead_follow_up / lead_voucher /
-- agency_setting, plus the ALTERs on lead / lead_proposal / lead_timeline / client_invoice /
-- lead_members that those new tables imply.
--
-- Decisions taken (see the phase-2 plan for the full reasoning):
--   - lead.source / lead.categories widen from fixed enums to agency-configurable free text,
--     validated against agency_setting rather than a Java enum. LeadSource/LeadCategory are
--     retired on the Java side in the same change as this migration.
--   - lead_members' five legacy visa booleans are dropped outright - nothing is deployed, and
--     the per-service visa_checklists JSONB on lead_service replaces them. Keeping both would
--     be two places the same tick could be written.
--   - client_invoice is extended in place (lead_id/service_id/service_label/description)
--     rather than adding a second lead-scoped invoice table.

-- ---------------------------------------------------------------------------
-- LEAD_SERVICE - one row per service instance on a lead (a lead may hold any
-- number of Flight/Hotel/Visa/Transfer instances). One wide table for all four
-- types rather than four narrow tables, because a lead's service list is read
-- as one set (the Services tab, the cross-lead board) far more often than any
-- one type is read alone.
--
-- label is STORED, not computed on read: the cross-lead Services board never
-- loads a service's sibling set, so the four-rule naming ladder (single
-- instance -> plain type; else natural field; duplicate source -> ordinal;
-- blank source -> positional) runs in the service layer on every write that
-- adds, removes or renames a service on the lead, and re-derives label for
-- every sibling in the same transaction.
--
-- date_from/date_to are likewise derived (per type) on every write, not left
-- for the client to compute, because the lead-level travel window is a roll-up
-- across every non-cancelled service's dates.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_service (
    id                       VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id                  VARCHAR(36) NOT NULL,
    client_id                VARCHAR(36) NOT NULL,
    client_name              VARCHAR(150) NOT NULL,
    lead_destination         VARCHAR(150) NOT NULL,
    lead_status              VARCHAR(20) NOT NULL,
    type                     VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    label                    VARCHAR(200) NOT NULL,
    sort_order               INTEGER NOT NULL DEFAULT 0,
    assigned_agent_id        VARCHAR(36),
    assigned_agent_name      VARCHAR(150),
    preferences              TEXT[] NOT NULL DEFAULT '{}',
    due_date                 DATE,
    date_from                DATE,
    date_to                  DATE,
    net_total                NUMERIC(19, 2) NOT NULL DEFAULT 0,
    selling_total            NUMERIC(19, 2) NOT NULL DEFAULT 0,

    -- Flight
    flight_trip_type         VARCHAR(20),
    flight_cabin             VARCHAR(20),
    flight_sectors           JSONB NOT NULL DEFAULT '[]',

    -- Hotel
    hotel_city               VARCHAR(150),
    hotel_check_in           DATE,
    hotel_check_out          DATE,
    hotel_nights             INTEGER,
    hotel_rooms              INTEGER,

    -- Visa - visa_checklists is keyed by member_id, never by array index, and is read only
    -- through one accessor (mirrors visaChecklistFor() on the frontend). One lead may hold two
    -- Visa services (two countries) with two independent checklists, which is why this lives
    -- on the service row and not on lead_members any more.
    visa_source_city         VARCHAR(150),
    visa_source_country      VARCHAR(100),
    visa_country             VARCHAR(100),
    visa_intended_travel_date DATE,
    visa_appointment_date    DATE,
    visa_checklists          JSONB NOT NULL DEFAULT '{}',

    -- Transfer - transfer_time is a plain 'HH:mm' local clock string, never a timestamp.
    transfer_vehicle_type    VARCHAR(50),
    transfer_pickup          VARCHAR(150),
    transfer_dropoff         VARCHAR(150),
    transfer_date            DATE,
    transfer_time            VARCHAR(5),
    transfer_passengers      INTEGER,

    created_at               TIMESTAMP,
    created_by               VARCHAR(36),
    updated_at               TIMESTAMP,
    updated_by               VARCHAR(36)
);

CREATE INDEX idx_lead_service_lead ON lead_service (lead_id, sort_order);
CREATE INDEX idx_lead_service_agent ON lead_service (assigned_agent_id);
CREATE INDEX idx_lead_service_type_status ON lead_service (type, status);
CREATE INDEX idx_lead_service_dates ON lead_service (date_from);

-- ---------------------------------------------------------------------------
-- LEAD_FOLLOW_UP - a promise to chase something, scoped to a service TYPE
-- (nullable = trip-level), never to one service instance. "Chase the embassy
-- Monday" is Visa work whichever visa it is about.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_follow_up (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id             VARCHAR(36) NOT NULL,
    client_name         VARCHAR(150) NOT NULL,
    lead_destination    VARCHAR(150) NOT NULL,
    service_type        VARCHAR(20),
    due_date            DATE NOT NULL,
    note                VARCHAR(500) NOT NULL,
    assigned_agent_id   VARCHAR(36) NOT NULL,
    assigned_agent_name VARCHAR(150) NOT NULL,
    status              VARCHAR(10) NOT NULL,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP,
    created_by          VARCHAR(36)
);

CREATE INDEX idx_lead_follow_up_lead ON lead_follow_up (lead_id, status, due_date);
CREATE INDEX idx_lead_follow_up_agent ON lead_follow_up (assigned_agent_id, status, due_date);

-- ---------------------------------------------------------------------------
-- LEAD_VOUCHER - a PNR/supplier reference, optionally tied to one service
-- (a flight PNR is a fact about that flight). file_key is opaque, from
-- FileStorageService, never a filesystem path.
-- ---------------------------------------------------------------------------
CREATE TABLE lead_voucher (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    lead_id          VARCHAR(36) NOT NULL,
    service_id       VARCHAR(36),
    service_label    VARCHAR(200),
    supplier         VARCHAR(150) NOT NULL,
    reference_number VARCHAR(100) NOT NULL,
    voucher_date     DATE NOT NULL,
    file_key         VARCHAR(500),
    notes            TEXT,
    created_at       TIMESTAMP,
    created_by       VARCHAR(36)
);

CREATE INDEX idx_lead_voucher_lead ON lead_voucher (lead_id);
CREATE INDEX idx_lead_voucher_service ON lead_voucher (service_id);

-- ---------------------------------------------------------------------------
-- AGENCY_SETTING - replaces the frontend's localStorage-only lead sources,
-- travel categories, document types and per-service preferences. Preferences
-- are matched on lead_service.preferences by NAME, not id, same as before.
-- ---------------------------------------------------------------------------
CREATE TABLE agency_setting (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    kind         VARCHAR(30) NOT NULL,
    service_type VARCHAR(20),
    name         VARCHAR(150) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP
);

CREATE INDEX idx_agency_setting_kind ON agency_setting (kind, is_active);

-- ---------------------------------------------------------------------------
-- ALTER LEAD - trip-level fields the service spine rolls up into, plus
-- widening source/categories off the fixed Java enums onto agency_setting.
-- follow_up_date's meaning also changes here (no column change, semantics
-- only): it becomes a derived cache of the earliest OPEN lead_follow_up.due_date,
-- recomputed in the same transaction as any follow-up insert/complete/delete,
-- and stops being directly writable by the client.
-- ---------------------------------------------------------------------------
ALTER TABLE lead ALTER COLUMN source TYPE VARCHAR(100);
ALTER TABLE lead ALTER COLUMN categories TYPE TEXT[];
ALTER TABLE lead ADD COLUMN special_notes TEXT;
ALTER TABLE lead ADD COLUMN travel_preferences TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE lead ADD COLUMN quoted_net_total NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN quoted_selling_total NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN open_follow_ups INTEGER NOT NULL DEFAULT 0;
ALTER TABLE lead ADD COLUMN service_count INTEGER NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- ALTER LEAD_PROPOSAL / LEAD_TIMELINE - service_id ties a quotation line or a
-- timeline event to the service it belongs to; NULL means trip-level.
-- ---------------------------------------------------------------------------
ALTER TABLE lead_proposal ADD COLUMN service_id VARCHAR(36);
ALTER TABLE lead_proposal ADD COLUMN service_label VARCHAR(200);
CREATE INDEX idx_lead_proposal_service ON lead_proposal (service_id);

ALTER TABLE lead_timeline ADD COLUMN service_id VARCHAR(36);
CREATE INDEX idx_lead_timeline_service ON lead_timeline (service_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- ALTER CLIENT_INVOICE - lead_id NULL means a direct client invoice outside
-- any lead (unchanged behaviour); service_id/service_label let one invoice
-- line be traced back to the flight or hotel it billed for.
-- ---------------------------------------------------------------------------
ALTER TABLE client_invoice ADD COLUMN lead_id VARCHAR(36);
ALTER TABLE client_invoice ADD COLUMN service_id VARCHAR(36);
ALTER TABLE client_invoice ADD COLUMN service_label VARCHAR(200);
ALTER TABLE client_invoice ADD COLUMN description VARCHAR(255);
CREATE INDEX idx_client_invoice_lead ON client_invoice (lead_id);

-- ---------------------------------------------------------------------------
-- ALTER LEAD_MEMBERS - drop the five legacy visa booleans (replaced by
-- lead_service.visa_checklists, per traveller AND per visa application); add
-- fare_class, recomputed from dob against the lead's travel window on every
-- traveller write and stored here purely for list-query speed - never the
-- source of truth, which stays PaxTypeCalculator against live dates.
-- ---------------------------------------------------------------------------
ALTER TABLE lead_members DROP COLUMN passport_collected;
ALTER TABLE lead_members DROP COLUMN photos_collected;
ALTER TABLE lead_members DROP COLUMN forms_filled;
ALTER TABLE lead_members DROP COLUMN submitted_to_embassy;
ALTER TABLE lead_members DROP COLUMN visa_approved;
ALTER TABLE lead_members ADD COLUMN fare_class VARCHAR(10);
