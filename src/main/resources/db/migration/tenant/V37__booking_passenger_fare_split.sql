-- Lets a captured passenger's fare split into a base fare and a taxes figure for the printed
-- invoice - "Fare plus taxes (two columns)", the middle ground between one blended amount and
-- the legacy system's full Basic/YQ/YR/K3/OC breakdown. tax_amount defaults to 0, so every
-- existing passenger's fareAmount keeps meaning exactly what it always did (the whole amount);
-- the invoice line's unitPrice is unaffected until an agent actually captures a non-zero split.

ALTER TABLE booking_passenger ADD COLUMN tax_amount NUMERIC(19,2) NOT NULL DEFAULT 0;

-- Display-only mirror on the frozen invoice line - never read by TaxEngine or any total
-- calculation, purely what the printed invoice's Fare/Taxes columns show. Defaults keep an
-- existing line's total reading exactly as it always has (fare_amount = unit_price, taxes = 0).
ALTER TABLE invoice_line_item ADD COLUMN fare_amount NUMERIC(19,2);
ALTER TABLE invoice_line_item ADD COLUMN tax_amount NUMERIC(19,2);
