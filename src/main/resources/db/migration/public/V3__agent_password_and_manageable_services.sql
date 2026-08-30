-- department stops being a required classification and becomes a display-only leftover -
-- manageable_services is now the field that decides what an agent may work on (blueprint
-- §2.5: additive DDL - existing rows keep their department, nothing backfills them).
ALTER TABLE agent ALTER COLUMN department DROP NOT NULL;

ALTER TABLE agent ADD COLUMN manageable_services TEXT[] NOT NULL DEFAULT '{}';
