-- Every agency gets one system client, "Walk-in Client": a platform booking made without a client (or by an agency
-- without the CRM) is stamped with it, so every booking has a client. ClientService refuses to edit or deactivate it;
-- only its travellers change. Its identifier is WALK-IN, or WALK-IN-VOYRA when an active client already holds WALK-IN
-- (the active-identifier index must not fail the migration). The primary member is created with it, as for any client.

ALTER TABLE client ADD COLUMN is_system boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX uq_client_system ON client (is_system) WHERE is_system;

WITH walk_in AS (
    INSERT INTO client (id, identifier, name, type, is_active, is_system, created_at, created_by)
    SELECT gen_random_uuid()::text,
           CASE WHEN EXISTS (SELECT 1 FROM client WHERE identifier = 'WALK-IN' AND is_active)
                THEN 'WALK-IN-VOYRA' ELSE 'WALK-IN' END,
           'Walk-in Client', 'B2C', TRUE, TRUE, now(), 'system'
    RETURNING id
)
INSERT INTO member (member_id, client_id, name, type, relation, is_active, created_at, created_by)
SELECT gen_random_uuid()::text, id, 'Walk-in Client', 'CLIENT', 'SELF', TRUE, now(), 'system'
FROM walk_in;
