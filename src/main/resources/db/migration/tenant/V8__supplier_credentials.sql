-- Per-agency credentials for an external supplier search integration (e.g. Tripjack). Each
-- agency brings its own account rather than sharing a platform-level one, so this is a
-- tenant-schema table, not a column on the public `tenant` row - a mis-written query here is
-- structurally confined to one agency's schema, the same guarantee every other tenant table
-- already relies on. One row per provider: the environment flag decides whether it is UAT or
-- production, rather than storing both at once.
CREATE TABLE supplier_credential (
    id                 VARCHAR(36) NOT NULL PRIMARY KEY,
    provider           VARCHAR(30) NOT NULL,
    environment        VARCHAR(10) NOT NULL,
    base_url           VARCHAR(255) NOT NULL,
    api_key_encrypted  VARCHAR(1024) NOT NULL,
    user_id_encrypted  VARCHAR(1024),
    is_active          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP,
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(36)
);

CREATE UNIQUE INDEX idx_supplier_credential_provider ON supplier_credential (provider);
