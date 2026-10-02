-- Multi-branch: column now, feature later (ACCOUNTING_EXPANSION_ARCHITECTURE.md §6, Decision 6).
-- A branch is an intra-agency partition, not a tenant - tenancy stays schema-per-agency. This
-- migration is purely additive: branch_id is nullable everywhere, every existing row reads as
-- NULL ("agency level"), and no service code populates or filters on it yet. Branch-scoped
-- authorization, per-branch numbering and consolidation reporting are explicitly out of scope -
-- the FRD gives multi-branch one sentence with no persona, no user story, no acceptance criteria.

CREATE TABLE branch (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    code          VARCHAR(20),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_date  TIMESTAMP
);

ALTER TABLE invoice ADD COLUMN branch_id VARCHAR(36);
CREATE INDEX idx_invoice_branch ON invoice (branch_id);

ALTER TABLE supplier_invoice ADD COLUMN branch_id VARCHAR(36);
CREATE INDEX idx_supplier_invoice_branch ON supplier_invoice (branch_id);

ALTER TABLE payment_receipt ADD COLUMN branch_id VARCHAR(36);
CREATE INDEX idx_payment_receipt_branch ON payment_receipt (branch_id);

ALTER TABLE supplier_payment ADD COLUMN branch_id VARCHAR(36);
CREATE INDEX idx_supplier_payment_branch ON supplier_payment (branch_id);

ALTER TABLE credit_note ADD COLUMN branch_id VARCHAR(36);
CREATE INDEX idx_credit_note_branch ON credit_note (branch_id);
