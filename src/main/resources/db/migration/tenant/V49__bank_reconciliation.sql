-- Decision 4 (ACCOUNTING_EXPANSION_ARCHITECTURE.md) - CSV-only bank statement import and the
-- two-tier match engine. bank_statement_profile is mandatory (Rule 4.1.1): every bank emits a
-- different column order, date format and sign convention, so an import without a profile is
-- rejected at the service layer, not expressed as a DB constraint.

CREATE TABLE bank_account (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    account_name         VARCHAR(120) NOT NULL,
    account_number       VARCHAR(40),
    ifsc                 VARCHAR(20),
    branch               VARCHAR(120),
    currency_code        VARCHAR(3) NOT NULL DEFAULT 'INR',
    ledger_account_code  VARCHAR(10) NOT NULL,
    opening_balance      NUMERIC(19,2) NOT NULL DEFAULT 0,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_date         TIMESTAMP
);

CREATE TABLE bank_statement_profile (
    id                 VARCHAR(36) NOT NULL PRIMARY KEY,
    bank_name          VARCHAR(120) NOT NULL,
    file_format        VARCHAR(20) NOT NULL DEFAULT 'CSV',
    date_format        VARCHAR(30) NOT NULL,
    column_map         TEXT NOT NULL,
    amount_convention  VARCHAR(30) NOT NULL,
    created_date       TIMESTAMP
);

CREATE TABLE bank_statement_import (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    bank_account_id VARCHAR(36) NOT NULL,
    profile_id     VARCHAR(36) NOT NULL,
    file_name      VARCHAR(255),
    storage_key    VARCHAR(500),
    period_from    DATE,
    period_to      DATE,
    row_count      INT NOT NULL DEFAULT 0,
    duplicate_count INT NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,
    imported_at    TIMESTAMP,
    imported_by    VARCHAR(36)
);
CREATE INDEX idx_bank_statement_import_account ON bank_statement_import (bank_account_id);

-- Rule 4.2.2 - duplicate protection. bank_reference is frequently blank on real statements;
-- Postgres treats each NULL as distinct in a unique index, so two referenceless lines on the
-- same account/date/amount are NOT caught by this alone - an accepted limitation, not an oversight.
CREATE TABLE bank_transaction (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    import_id            VARCHAR(36) NOT NULL,
    bank_account_id      VARCHAR(36) NOT NULL,
    txn_date             DATE NOT NULL,
    value_date           DATE,
    description          VARCHAR(500),
    bank_reference       VARCHAR(100),
    amount               NUMERIC(19,2) NOT NULL,
    direction            VARCHAR(10) NOT NULL,
    running_balance      NUMERIC(19,2),
    match_status         VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
    matched_source_type  VARCHAR(30),
    matched_source_id    VARCHAR(36),
    match_score          NUMERIC(5,2),
    matched_at           TIMESTAMP,
    matched_by           VARCHAR(36)
);
CREATE UNIQUE INDEX uq_bank_transaction_dup ON bank_transaction (bank_account_id, txn_date, amount, bank_reference);
CREATE INDEX idx_bank_transaction_import ON bank_transaction (import_id);
CREATE INDEX idx_bank_transaction_account_status ON bank_transaction (bank_account_id, match_status);

CREATE TABLE bank_match_rule (
    id                   VARCHAR(36) NOT NULL PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL,
    match_field          VARCHAR(20) NOT NULL,
    pattern              VARCHAR(200) NOT NULL,
    target_account_code  VARCHAR(10) NOT NULL,
    auto_post            BOOLEAN NOT NULL DEFAULT FALSE,
    priority             INT NOT NULL DEFAULT 100,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_bank_match_rule_priority ON bank_match_rule (priority);

-- Rule 4.1.3 - ties a real bank transaction to the payment it settles, beside the existing
-- free-text bankAccountLabel snapshot (not replacing it - denormalisation-by-design rule).
ALTER TABLE supplier_payment ADD COLUMN bank_account_id VARCHAR(36);
CREATE INDEX idx_supplier_payment_bank_account ON supplier_payment (bank_account_id);

ALTER TABLE payment_receipt ADD COLUMN bank_account_id VARCHAR(36);
CREATE INDEX idx_payment_receipt_bank_account ON payment_receipt (bank_account_id);
