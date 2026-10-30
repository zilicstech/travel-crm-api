-- Voyra Platform becomes the one system vendor: the CRM mirror of a platform booking finds it by is_system
-- (not by name), and the agency can't rename, retype or deactivate it. Default vendors are no longer seeded.
ALTER TABLE vendor ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;

INSERT INTO vendor (id, name, service_types, is_active, sort_order, created_at)
SELECT 'voyra-platform-' || substr(md5(random()::text), 1, 20), 'Voyra Platform', ARRAY['FLIGHT','HOTEL'], TRUE, 0, now()
WHERE NOT EXISTS (SELECT 1 FROM vendor WHERE LOWER(name) = 'voyra platform');

-- Prefer the live row; an owner may have switched it off, and the mirror needs it active.
UPDATE vendor SET is_system = TRUE, is_active = TRUE, name = 'Voyra Platform'
WHERE id = (SELECT id FROM vendor WHERE LOWER(name) = 'voyra platform'
            ORDER BY is_active DESC, created_at NULLS LAST, id LIMIT 1);

CREATE UNIQUE INDEX uq_vendor_system ON vendor ((is_system)) WHERE is_system;
