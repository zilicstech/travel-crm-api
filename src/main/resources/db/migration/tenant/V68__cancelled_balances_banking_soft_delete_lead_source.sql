-- A cancelled invoice or supplier bill owes nothing: cancelling now zeroes its balance due
-- (BUG-CRX-04/18). Bring the documents cancelled before that in line, so they stop showing as due/overdue.
UPDATE invoice SET balance_due = 0, balance_due_inr = 0 WHERE status = 'CANCELLED' AND (balance_due <> 0 OR balance_due_inr <> 0);
UPDATE supplier_invoice SET balance_due = 0, balance_due_inr = 0 WHERE status = 'CANCELLED' AND (balance_due <> 0 OR balance_due_inr <> 0);

-- Bank accounts, match rules and statement profiles can now be deleted (BUG-CRX-22) - soft, like
-- everything else since V55: the row keeps its id for the imports and lines that point at it.
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE bank_match_rule ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE bank_statement_profile ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- A lead created with no source used to default to the raw "PHONE_CALL", which matches no lead-source
-- option and split the source report in two (BUG-CRX-25). The default is now the seeded "Phone Call".
UPDATE lead SET source = 'Phone Call' WHERE source = 'PHONE_CALL';
