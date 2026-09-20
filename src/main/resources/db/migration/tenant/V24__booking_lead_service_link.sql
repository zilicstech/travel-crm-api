-- ---------------------------------------------------------------------------
-- BOOKING becomes lead/service-owned. A lead's service can carry several
-- bookings (a round trip is two flight bookings), each with fields matching
-- its service type. booking.id stays the join key for invoice/payment_receipt/
-- credit_note/customer_ledger_entry/feedback/feedback_link/supplier_invoice -
-- everything here is additive, nothing is re-keyed.
--
-- created_by/updated_at/updated_by already exist on booking (V10__audit_log.sql)
-- and are not repeated here.
-- ---------------------------------------------------------------------------

-- 1. Lead/service ownership + denormalized scoping keys. NULL lead_id/service_id
--    means a standalone (walk-in) booking, created directly from the Bookings tab -
--    those never widen past their own agent (see BookingAccessChecker).
ALTER TABLE booking ADD COLUMN lead_id            VARCHAR(36);
ALTER TABLE booking ADD COLUMN service_id         VARCHAR(36);
ALTER TABLE booking ADD COLUMN service_type       VARCHAR(20);
ALTER TABLE booking ADD COLUMN service_label      VARCHAR(200);
ALTER TABLE booking ADD COLUMN service_agent_id   VARCHAR(36);
ALTER TABLE booking ADD COLUMN service_agent_name VARCHAR(150);
ALTER TABLE booking ADD COLUMN notes              TEXT;

CREATE INDEX idx_booking_lead    ON booking (lead_id);
CREATE INDEX idx_booking_service ON booking (service_id);
-- Serves the agent-scope predicate (service_type IN (:manageableTypes)) in one index scan.
CREATE INDEX idx_booking_scope   ON booking (service_type, service_agent_id);

-- 2. Type blocks, mirroring how lead_service stores its own four types. Flight
--    reuses pnr/ticket_no/airline/journey_date/return_date rather than duplicating
--    them - those five already drive the calendar feed and deadline escalation.
ALTER TABLE booking ADD COLUMN flight_number         VARCHAR(20);
ALTER TABLE booking ADD COLUMN flight_from           VARCHAR(100);
ALTER TABLE booking ADD COLUMN flight_to             VARCHAR(100);
ALTER TABLE booking ADD COLUMN flight_cabin          VARCHAR(20);

ALTER TABLE booking ADD COLUMN hotel_confirmation_no VARCHAR(100);
ALTER TABLE booking ADD COLUMN hotel_name            VARCHAR(200);
ALTER TABLE booking ADD COLUMN hotel_city            VARCHAR(150);
ALTER TABLE booking ADD COLUMN hotel_check_in        DATE;
ALTER TABLE booking ADD COLUMN hotel_check_out       DATE;
ALTER TABLE booking ADD COLUMN hotel_room_type       VARCHAR(100);
ALTER TABLE booking ADD COLUMN hotel_board_basis     VARCHAR(50);
ALTER TABLE booking ADD COLUMN hotel_rooms           INTEGER;

ALTER TABLE booking ADD COLUMN visa_application_no   VARCHAR(100);
ALTER TABLE booking ADD COLUMN visa_country          VARCHAR(100);
ALTER TABLE booking ADD COLUMN visa_applied_date     DATE;
ALTER TABLE booking ADD COLUMN visa_appointment_date DATE;
ALTER TABLE booking ADD COLUMN visa_issued_date      DATE;

-- transfer_time is a plain 'HH:mm' local clock string, never a timestamp -
-- the lead_service precedent for the same field.
ALTER TABLE booking ADD COLUMN transfer_voucher_no   VARCHAR(100);
ALTER TABLE booking ADD COLUMN transfer_vehicle_type VARCHAR(50);
ALTER TABLE booking ADD COLUMN transfer_pickup       VARCHAR(150);
ALTER TABLE booking ADD COLUMN transfer_dropoff      VARCHAR(150);
ALTER TABLE booking ADD COLUMN transfer_date         DATE;
ALTER TABLE booking ADD COLUMN transfer_time         VARCHAR(5);

-- 3. Voucher documents attached to a booking - several per booking (e-ticket,
--    hotel voucher, insurance), each with its own reference and file.
CREATE TABLE booking_document (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    booking_id       VARCHAR(36) NOT NULL,
    doc_type         VARCHAR(30) NOT NULL,
    reference_number VARCHAR(100),
    issued_date      DATE,
    file_key         VARCHAR(500),
    file_name        VARCHAR(255),
    content_type     VARCHAR(150),
    notes            TEXT,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMP,
    created_by       VARCHAR(36)
);
CREATE INDEX idx_booking_document_booking ON booking_document (booking_id, sort_order);

-- 4. Many tax invoices per booking become legal (advance + balance). The
--    invariant that actually needed a DB guard was never "one invoice per
--    booking" - it was "never two editable drafts competing for the same
--    booking". Narrow the unique index to exactly that.
DROP INDEX idx_invoice_booking_live;
CREATE UNIQUE INDEX idx_invoice_booking_draft ON invoice (booking_id)
    WHERE booking_id IS NOT NULL AND document_type = 'TAX_INVOICE' AND status = 'DRAFT';

-- 5. lead_voucher -> booking + booking_document. ONE booking PER VOUCHER, not
--    per service: a voucher carries exactly one reference and one supplier, so
--    merging siblings would discard a reference and fabricate a grouping nobody
--    asked for. Money is zero on every migrated row - vouchers never carried a
--    net/selling figure. lead_voucher itself is left in place, unread, for one
--    release (the V12 refund_status precedent) rather than dropped here.
-- A session-scoped temp table, not schema-scoped: pg_temp sits ahead of every explicit
-- schema on the search path regardless of which tenant it is currently pointed at, so a
-- name collides across tenants when the same session runs several tenants' migrations back
-- to back (exactly what TenantMigrationStartupRunner does at boot). Drop defensively first.
DROP TABLE IF EXISTS voucher_migration;
CREATE TEMP TABLE voucher_migration AS
SELECT v.id AS voucher_id, gen_random_uuid()::text AS booking_id, v.*
FROM lead_voucher v;

INSERT INTO booking (
    id, client_id, client_name, agent_id, agent_name, type, destination,
    lead_id, service_id, service_type, service_label, service_agent_id, service_agent_name,
    supplier, notes, booking_date, journey_date,
    net_cost, selling_price, profit, booking_status, payment_status,
    refund_state, invoiced_total_inr, received_total_inr, refunded_total_inr,
    payment_status_source, created_date, created_by,
    pnr, hotel_confirmation_no, visa_application_no, transfer_voucher_no
)
SELECT
    m.booking_id,
    l.client_id, l.client_name,
    COALESCE(s.assigned_agent_id, m.created_by, l.created_by),
    COALESCE(s.assigned_agent_name, l.created_by_name, 'Unknown'),
    CASE s.type WHEN 'FLIGHT' THEN 'FLIGHT' WHEN 'HOTEL' THEN 'HOTEL'
                WHEN 'VISA'   THEN 'VISA'   WHEN 'TRANSFER' THEN 'TRANSFER'
                ELSE 'PACKAGE' END,                       -- NULL service_id -> PACKAGE
    l.destination,
    m.lead_id, m.service_id, s.type, m.service_label,
    s.assigned_agent_id, s.assigned_agent_name,
    m.supplier, m.notes, m.voucher_date, m.voucher_date,
    0, 0, 0, 'CONFIRMED', 'PENDING',
    'NOT_APPLICABLE', 0, 0, 0,
    'MANUAL', m.created_at, m.created_by,
    CASE WHEN s.type = 'FLIGHT'   THEN m.reference_number END,
    CASE WHEN s.type = 'HOTEL'    THEN m.reference_number END,
    CASE WHEN s.type = 'VISA'     THEN m.reference_number END,
    CASE WHEN s.type = 'TRANSFER' THEN m.reference_number END
FROM voucher_migration m
JOIN lead l ON l.id = m.lead_id
LEFT JOIN lead_service s ON s.id = m.service_id;

-- file_key/file_name/content_type carry over VERBATIM - FileStorageService.retrieve()
-- resolves a key on its own, so no bytes move; the key simply keeps its historical
-- 'lead-vouchers/' segment forever, which is harmless because the key is opaque by
-- contract and never leaves the service layer.
INSERT INTO booking_document (
    id, booking_id, doc_type, reference_number, issued_date,
    file_key, file_name, content_type, notes, sort_order, created_at, created_by
)
SELECT gen_random_uuid()::text, m.booking_id,
       CASE s.type WHEN 'FLIGHT' THEN 'E_TICKET' WHEN 'HOTEL' THEN 'HOTEL_VOUCHER'
                   WHEN 'VISA'   THEN 'VISA_COPY' WHEN 'TRANSFER' THEN 'TRANSFER_VOUCHER'
                   ELSE 'OTHER' END,
       m.reference_number, m.voucher_date,
       m.file_key, m.file_name, m.content_type, m.notes, 0, m.created_at, m.created_by
FROM voucher_migration m
LEFT JOIN lead_service s ON s.id = m.service_id;

-- Services that now hold a booking flip to BOOKED - the same rule the app enforces live.
UPDATE lead_service SET status = 'BOOKED'
WHERE status <> 'CANCELLED'
  AND id IN (SELECT service_id FROM voucher_migration WHERE service_id IS NOT NULL);

DROP TABLE voucher_migration;
