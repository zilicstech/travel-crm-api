-- ChartOfAccountsSeedService upserts by code and never updates an existing row (Rule 1.4.2 -
-- "left exactly as it is, including if a human has since renamed it"), so the per-category
-- sales/purchase accounts already seeded before this release keep parent_code NULL forever even
-- after the service starts passing SystemAccount.SALES/PURCHASES as the parent on new tenants.
-- One-time, idempotent (only touches rows that are still unparented), matches the seed service's
-- own code ranges exactly: 4010-4080 under 4000 Sales, 5010-5080 under 5000 Purchases. The two
-- summary accounts themselves (4000, 5000) are inserted by ChartOfAccountsSeedService's normal
-- startup pass, which always runs after migrations - this migration only needs to repoint the
-- children, not create the parents.

UPDATE ledger_account
SET parent_code = '4000'
WHERE code ~ '^40[1-8]0$' AND parent_code IS NULL;

UPDATE ledger_account
SET parent_code = '5000'
WHERE code ~ '^50[1-8]0$' AND parent_code IS NULL;
