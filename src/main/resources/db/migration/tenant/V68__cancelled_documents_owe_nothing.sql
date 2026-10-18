-- A cancelled invoice or supplier bill owes nothing: cancelling now zeroes its balance due
-- (BUG-CRX-04/18). Bring the documents cancelled before that in line, so they stop showing as due/overdue.
UPDATE invoice SET balance_due = 0, balance_due_inr = 0 WHERE status = 'CANCELLED' AND (balance_due <> 0 OR balance_due_inr <> 0);
UPDATE supplier_invoice SET balance_due = 0, balance_due_inr = 0 WHERE status = 'CANCELLED' AND (balance_due <> 0 OR balance_due_inr <> 0);
