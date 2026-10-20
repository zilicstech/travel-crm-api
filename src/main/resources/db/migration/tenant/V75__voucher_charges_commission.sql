-- A voucher's fare charges (Basic, YQ, YR, K3, OC, other tax, processing, other charges - in its purchase
-- currency) and the commission its supplier pays the agency, with the TDS the supplier deducts on it.
ALTER TABLE booking_document
    ADD COLUMN charges JSONB,
    ADD COLUMN commission_mode VARCHAR(10),
    ADD COLUMN commission_value NUMERIC(14, 2),
    ADD COLUMN supplier_tds_rate NUMERIC(5, 2),
    ADD COLUMN commission NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_tds NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN commission_inr NUMERIC(14, 2) NOT NULL DEFAULT 0;

-- The booking's commission (base), beside net_cost: profit = selling price - net cost + commission.
ALTER TABLE booking ADD COLUMN commission NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- On the voucher's supplier bill, in the bill's currency: what is owed is grand total - commission + TDS.
ALTER TABLE supplier_invoice
    ADD COLUMN commission NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN supplier_tds NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- The voucher's charges in base, kept on the invoice line it was billed on.
ALTER TABLE invoice_line_item ADD COLUMN charges JSONB;
