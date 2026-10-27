-- Every tenant gets the "Voyra Platform" vendor and the "Voyra Wallet (advance to supplier)" ledger
-- account that the mirror of a platform booking posts to. VendorDefaults only seeds a tenant with no
-- vendors other than this one, so it cannot be relied on to create it. Idempotent: safe on a tenant that has either already.
INSERT INTO vendor (id, name, service_types, is_active, sort_order, created_at)
SELECT 'voyra-platform-' || substr(md5(random()::text), 1, 20), 'Voyra Platform', ARRAY['FLIGHT','HOTEL'], TRUE, 0, now()
WHERE NOT EXISTS (SELECT 1 FROM vendor WHERE LOWER(name) = 'voyra platform' AND is_active);

-- 1310 sits under 1300 Supplier Advances (same code as SystemAccount.VOYRA_WALLET_ADVANCE).
INSERT INTO ledger_account (id, code, name, account_type, parent_code, is_system, is_control, is_active, created_date)
SELECT 'voyra-wallet-' || substr(md5(random()::text), 1, 22), '1310', 'Voyra Wallet (advance to supplier)',
       'ASSET', '1300', TRUE, FALSE, TRUE, now()
WHERE NOT EXISTS (SELECT 1 FROM ledger_account WHERE code = '1310');

ALTER TABLE booking ADD COLUMN IF NOT EXISTS platform_booking_id VARCHAR(36) UNIQUE;
