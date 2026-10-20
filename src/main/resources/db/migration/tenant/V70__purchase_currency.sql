-- A voucher or quote line can be bought, or carry a flat markup, in a currency other than the
-- agency's base. net_cost / markup_value stay the base-currency figures; these keep what was typed.
ALTER TABLE booking_document
    ADD COLUMN purchase_currency VARCHAR(3),
    ADD COLUMN purchase_price NUMERIC(14, 2),
    ADD COLUMN purchase_rate NUMERIC(18, 6),
    ADD COLUMN markup_currency VARCHAR(3),
    ADD COLUMN markup_input NUMERIC(14, 2),
    ADD COLUMN markup_rate NUMERIC(18, 6);

ALTER TABLE lead_proposal
    ADD COLUMN purchase_currency VARCHAR(3),
    ADD COLUMN purchase_price NUMERIC(14, 2),
    ADD COLUMN purchase_rate NUMERIC(18, 6),
    ADD COLUMN markup_currency VARCHAR(3),
    ADD COLUMN markup_input NUMERIC(14, 2),
    ADD COLUMN markup_rate NUMERIC(18, 6);
