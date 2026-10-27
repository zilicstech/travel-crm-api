-- Subscription billing (B2B slice 5, Task 4).
-- paid_ref: the gateway payment id (paid_via GATEWAY) or the bank reference (BANK) - the webhook's idempotency check.
ALTER TABLE subscription_invoice ADD COLUMN paid_ref VARCHAR(100);

-- 14/7/1-day expiry notices: the smallest threshold already sent for the end date in expiry_notice_for,
-- so each notice goes once even when the daily job runs twice or misses a day.
ALTER TABLE subscription ADD COLUMN expiry_notice_days INT;
ALTER TABLE subscription ADD COLUMN expiry_notice_for TIMESTAMPTZ;
