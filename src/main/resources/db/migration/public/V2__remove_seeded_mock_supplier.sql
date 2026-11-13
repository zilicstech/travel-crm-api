-- Production starts without the mock supplier the baseline seeded (every switch was off, but it showed on the
-- Suppliers tab and there is no delete). Removed only while no platform booking names it, so booking history keeps
-- its supplier. Local QA creates its own MOCK row through the API (.claude/run/seed-mock-supplier.py, the e2e fixtures).

DELETE FROM platform_supplier_product p
USING platform_supplier_credential c
WHERE p.credential_id = c.id
  AND c.provider = 'MOCK' AND c.name = 'Mock supplier'
  AND NOT EXISTS (SELECT 1 FROM platform_booking b WHERE b.credential_id = c.id);

DELETE FROM platform_supplier_credential c
WHERE c.provider = 'MOCK' AND c.name = 'Mock supplier'
  AND NOT EXISTS (SELECT 1 FROM platform_booking b WHERE b.credential_id = c.id);
