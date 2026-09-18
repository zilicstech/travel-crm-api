-- Compliance-grade, entity-agnostic, append-only change record. Distinct from lead_timeline:
-- that table is the user-facing narrative for one lead ("Note added by Liam"); this one is the
-- field-level evidence for every auditable record in the agency. Neither writes to the other.
--
-- field_changes is JSONB rather than a child table because §8.4 forbids JPA associations, so a
-- child table would mean a hand-written second query and a Java group-by on every page read,
-- and a change tuple is never filtered on independently. Same call already taken for
-- lead_service.flight_sectors and lead_service.visa_checklists.
--
-- Values are stored stringified (never typed columns): a diff spans BigDecimal, enum, LocalDate
-- and String in the same list, and the only consumer renders them as text.
CREATE TABLE audit_log (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    entity_type   VARCHAR(30)  NOT NULL,
    entity_id     VARCHAR(36)  NOT NULL,
    entity_label  VARCHAR(200),
    action        VARCHAR(10)  NOT NULL,
    actor_id      VARCHAR(36)  NOT NULL,
    actor_name    VARCHAR(150) NOT NULL,
    field_changes JSONB        NOT NULL DEFAULT '[]',
    created_at    TIMESTAMP    NOT NULL
);

-- "show this booking's history" - the only per-record read path.
CREATE INDEX idx_audit_log_record  ON audit_log (entity_type, entity_id, created_at DESC);
-- Unfiltered agency-wide feed, which is the default and by far the hottest query.
CREATE INDEX idx_audit_log_created ON audit_log (created_at DESC);
-- The two agency-wide filters that are selective enough to be worth an index.
CREATE INDEX idx_audit_log_actor   ON audit_log (actor_id, created_at DESC);
CREATE INDEX idx_audit_log_type    ON audit_log (entity_type, created_at DESC);

-- booking/visa/client_invoice/supplier_invoice carried only created_date and no actor at all.
-- Additive and nullable: existing rows keep NULL rather than being back-filled with a lie about
-- who last touched them.
ALTER TABLE booking          ADD COLUMN created_by VARCHAR(36);
ALTER TABLE booking          ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE booking          ADD COLUMN updated_by VARCHAR(36);

ALTER TABLE visa             ADD COLUMN created_by VARCHAR(36);
ALTER TABLE visa             ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE visa             ADD COLUMN updated_by VARCHAR(36);

ALTER TABLE client_invoice   ADD COLUMN created_by VARCHAR(36);
ALTER TABLE client_invoice   ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE client_invoice   ADD COLUMN updated_by VARCHAR(36);

ALTER TABLE supplier_invoice ADD COLUMN created_by VARCHAR(36);
ALTER TABLE supplier_invoice ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE supplier_invoice ADD COLUMN updated_by VARCHAR(36);
