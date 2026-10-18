-- An explicit pass-through amount for a commission-agent invoice: the supplier's share that is
-- billed untaxed, the rest is the agency's fee. Set on a cancellation-charges invoice (supplier
-- charge kept vs agency fee); NULL means the fee is worked out from the booking's cost.
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS pass_through_amount NUMERIC(19,2);
