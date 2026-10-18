-- A cancellation's charges are asked in three parts: the airline's (or hotel's) charge, the
-- supplier's own fee, then the agency fee. cancellation_supplier_charge stays the total the
-- supplier side keeps (airline + supplier fee) so every refund and ledger figure is unchanged;
-- this column records the airline's part of it.
ALTER TABLE booking ADD COLUMN IF NOT EXISTS cancellation_airline_charge NUMERIC(19,2) NOT NULL DEFAULT 0;
