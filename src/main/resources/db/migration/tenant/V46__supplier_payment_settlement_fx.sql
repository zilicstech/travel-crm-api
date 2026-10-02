-- Decision 3.4 - a settlement rate must be able to differ from the bill's own rate, or a
-- realized forex gain/loss is never representable (ACCOUNTING_EXPANSION_ARCHITECTURE.md §3.4).
-- Nullable: every existing row keeps meaning exactly what it always did (fx_rate_to_inr was the
-- bill's rate, variance implicitly zero).

ALTER TABLE supplier_payment ADD COLUMN settlement_fx_rate NUMERIC(18,6);
ALTER TABLE supplier_payment ADD COLUMN fx_variance_inr NUMERIC(19,2);
