-- Objective market data, identical for every tenant - deliberately NOT per-tenant (see
-- ACCOUNTING_EXPANSION_ARCHITECTURE.md Decision 3.1). The per-document fx_rate_to_inr override
-- on invoices/bills stays in the tenant schema; this table is the daily reference rate it can
-- default from.
CREATE TABLE exchange_rate (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    rate_date      DATE          NOT NULL,
    base_currency  VARCHAR(3)    NOT NULL,
    quote_currency VARCHAR(3)    NOT NULL,
    rate           NUMERIC(18,6) NOT NULL,
    source         VARCHAR(30)   NOT NULL,
    fetched_at     TIMESTAMP
);

CREATE UNIQUE INDEX uq_exchange_rate_date_pair ON exchange_rate (rate_date, base_currency, quote_currency);
CREATE INDEX idx_exchange_rate_pair_date ON exchange_rate (base_currency, quote_currency, rate_date);
