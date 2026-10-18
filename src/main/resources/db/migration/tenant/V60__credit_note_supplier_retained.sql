-- How much of a cancellation credit note's kept amount the supplier kept (airline charge + supplier
-- fee), so a commission-agent credit note reverses the pass-through by exactly what was refunded
-- and the agency fee stays as the only income. NULL on a credit note not raised by a cancellation.
ALTER TABLE credit_note ADD COLUMN IF NOT EXISTS supplier_retained NUMERIC(19,2);
