-- Suppliers stop being name-only agency_setting rows and become real party records, the way
-- client already is. One row per vendor across every service type it serves (service_types is a
-- TEXT[] of ServiceType names, matching agent.manageable_services and lead_service.preferences -
-- the house style for multi-valued scalars; no join table, per §8.4).
--
-- Consumers keep referencing vendors by NAME (booking.supplier, lead_proposal.supplier,
-- supplier_invoice.supplier_name are unchanged). That is why name is unique per tenant and why a
-- rename re-syncs those snapshots in the same transaction, exactly as a client or agent rename
-- already does - see VendorService.
CREATE TABLE vendor (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    service_types     TEXT[]       NOT NULL DEFAULT '{}',
    contact_person    VARCHAR(150),
    phone             VARCHAR(20),
    email             VARCHAR(150),
    address           VARCHAR(500),
    gst_number        VARCHAR(15),
    notes             VARCHAR(1000),
    default_rate_note VARCHAR(255),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP,
    created_by        VARCHAR(36),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(36)
);

-- Scoped to active rows, mirroring idx_client_identifier: deactivating frees the name for reuse
-- after a merge or a data-entry mistake, while two live vendors can never share one.
CREATE UNIQUE INDEX uq_vendor_name_active    ON vendor (LOWER(name)) WHERE is_active;
-- Name lookups (the rename re-sync, and the AgencySetting SUPPLIER read shim) hit inactive rows too.
CREATE INDEX        idx_vendor_name          ON vendor (LOWER(name));
-- Served only by the containment form service_types @> ARRAY['FLIGHT'] - NOT the = ANY(...) form.
CREATE INDEX        idx_vendor_service_types ON vendor USING GIN (service_types);
CREATE INDEX        idx_vendor_active_sort   ON vendor (is_active, sort_order, name);

-- One-time backup before the destructive step below - forward-only Flyway has no other undo.
CREATE TABLE agency_setting_supplier_backup AS
SELECT * FROM agency_setting WHERE kind = 'SUPPLIER';

-- Carry the existing SUPPLIER settings across, collapsing the one-row-per-(type,name) shape into
-- one row per vendor. "Tripjack" currently exists twice (FLIGHT and HOTEL) and becomes a single
-- vendor with service_types = {FLIGHT,HOTEL} - which is the whole point of the new table.
INSERT INTO vendor (id, name, service_types, is_active, sort_order, created_at)
SELECT gen_random_uuid()::text,
       MIN(name),
       COALESCE(ARRAY_AGG(DISTINCT service_type) FILTER (WHERE service_type IS NOT NULL), '{}'),
       BOOL_OR(is_active),
       MIN(sort_order),
       MIN(created_at)
FROM agency_setting
WHERE kind = 'SUPPLIER'
GROUP BY LOWER(name);

-- Single source of truth from here on. The SUPPLIER kind survives one release in Java only as a
-- read-only compatibility shim (AgencySettingService) proxying to this table.
DELETE FROM agency_setting WHERE kind = 'SUPPLIER';
