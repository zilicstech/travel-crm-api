-- Tripjack statement imports (spec 5.1): each file once (by SHA-256 of its bytes), each (booking, type) line applied once.
CREATE TABLE platform_statement_import (
    id               VARCHAR(36)  PRIMARY KEY,
    file_name        VARCHAR(255) NOT NULL,
    file_sha256      VARCHAR(64)  NOT NULL,
    total_lines      INTEGER      NOT NULL,
    applied_lines    INTEGER      NOT NULL,
    duplicate_lines  INTEGER      NOT NULL,
    unmatched_lines  INTEGER      NOT NULL,
    imported_by      VARCHAR(36)  NOT NULL,
    imported_by_name VARCHAR(150),
    imported_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_platform_statement_import_sha UNIQUE (file_sha256)
);

CREATE TABLE platform_statement_line (
    id                  VARCHAR(36)   PRIMARY KEY,
    import_id           VARCHAR(36)   NOT NULL,
    line_no             INTEGER       NOT NULL,
    supplier_booking_id VARCHAR(120),
    pnr                 VARCHAR(40),
    type                VARCHAR(6)    NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    commission          NUMERIC(19,2) NOT NULL,
    line_date           DATE          NOT NULL,
    status              VARCHAR(10)   NOT NULL,
    reason              VARCHAR(300),
    platform_booking_id VARCHAR(36),
    CONSTRAINT ck_platform_statement_line_type CHECK (type IN ('SALE', 'REFUND')),
    CONSTRAINT ck_platform_statement_line_status CHECK (status IN ('APPLIED', 'DUPLICATE', 'UNMATCHED'))
);

-- the database backstop for "a line is applied once", even across concurrent imports
CREATE UNIQUE INDEX ux_platform_statement_line_applied ON platform_statement_line (platform_booking_id, type) WHERE status = 'APPLIED';
CREATE INDEX idx_platform_statement_line_import ON platform_statement_line (import_id, line_no);

CREATE INDEX idx_platform_booking_ticketed_at ON platform_booking (ticketed_at);
CREATE INDEX idx_platform_booking_supplier_booking_id ON platform_booking (supplier_booking_id);
CREATE INDEX idx_platform_booking_pnr ON platform_booking (pnr);
