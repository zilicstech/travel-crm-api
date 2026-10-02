-- The double-entry general ledger (Decision 1, ACCOUNTING_EXPANSION_ARCHITECTURE.md §1). The
-- two existing subsidiary ledgers (customer_ledger_entry, supplier_ledger_entry) are untouched -
-- they keep their own single-writer and idempotency semantics. This adds a parallel, reconcilable
-- double-entry layer: every posting site gains one extra, balanced journal_entry alongside its
-- existing subsidiary-ledger write. Nothing here changes an existing table's shape.

CREATE TABLE ledger_account (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    code          VARCHAR(10)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    account_type  VARCHAR(20)  NOT NULL,
    parent_code   VARCHAR(10),
    is_system     BOOLEAN      NOT NULL DEFAULT FALSE,
    is_control    BOOLEAN      NOT NULL DEFAULT FALSE,
    control_of    VARCHAR(30),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_date  TIMESTAMP
);

CREATE UNIQUE INDEX uq_ledger_account_code ON ledger_account (code);

CREATE TABLE journal_entry (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    entry_number      VARCHAR(40)  NOT NULL,
    entry_date        DATE         NOT NULL,
    financial_year    VARCHAR(9)   NOT NULL,
    source_type       VARCHAR(30)  NOT NULL,
    source_id         VARCHAR(36),
    purpose           VARCHAR(40)  NOT NULL,
    narration         VARCHAR(255) NOT NULL,
    booking_id        VARCHAR(36),
    branch_id         VARCHAR(36),
    status            VARCHAR(20)  NOT NULL,
    reverses_entry_id VARCHAR(36),
    created_at        TIMESTAMP,
    created_by        VARCHAR(36)
);

CREATE INDEX idx_journal_entry_date ON journal_entry (entry_date);
CREATE INDEX idx_journal_entry_source ON journal_entry (source_type, source_id);
CREATE INDEX idx_journal_entry_booking ON journal_entry (booking_id);

-- The idempotency guarantee (Rule 1.6.3): one entry per (source_type, source_id, purpose).
-- A second attempt to post the same event is a bug and must fail loudly, not silently no-op
-- or double-post - mirrors V21's unique index on customer_ledger_entry.
CREATE UNIQUE INDEX uq_journal_entry_source
    ON journal_entry (source_type, source_id, purpose)
    WHERE source_id IS NOT NULL;

CREATE TABLE journal_line (
    id                VARCHAR(36)   NOT NULL PRIMARY KEY,
    journal_entry_id  VARCHAR(36)   NOT NULL,
    line_no           INT           NOT NULL,
    account_code      VARCHAR(10)   NOT NULL,
    party_type        VARCHAR(20),
    party_id          VARCHAR(36),
    currency_code     VARCHAR(3)    NOT NULL DEFAULT 'INR',
    fx_rate_to_inr    NUMERIC(18,6) NOT NULL DEFAULT 1,
    debit_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit_amount     NUMERIC(19,2) NOT NULL DEFAULT 0,
    debit_amount_inr  NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit_amount_inr NUMERIC(19,2) NOT NULL DEFAULT 0,
    narration         VARCHAR(255),
    CONSTRAINT chk_journal_line_one_side CHECK (debit_amount = 0 OR credit_amount = 0)
);

CREATE INDEX idx_journal_line_entry ON journal_line (journal_entry_id);
CREATE INDEX idx_journal_line_account ON journal_line (account_code);
CREATE INDEX idx_journal_line_party ON journal_line (party_type, party_id);
