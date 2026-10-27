-- Owner self-serve upgrade (2026-10-27): an invoice raised for a higher plan; paying it switches the plan.
ALTER TABLE subscription_invoice ADD COLUMN target_plan VARCHAR(10);
ALTER TABLE subscription_invoice ADD CONSTRAINT subscription_invoice_target_plan_check CHECK (target_plan IN ('LITE','SUITE'));
