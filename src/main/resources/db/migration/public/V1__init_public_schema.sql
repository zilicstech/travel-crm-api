-- Global/shared schema: tenant (=Agency, doubles as the Owner login), platform admins,
-- agent logins (tenant-scoped but stored in public since login must resolve before the
-- tenant schema is known), and the public-proposal token index.

CREATE TABLE tenant (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    agency_name  VARCHAR(150) NOT NULL,
    owner_name   VARCHAR(150) NOT NULL,
    owner_email  VARCHAR(150) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_date TIMESTAMP
);

CREATE UNIQUE INDEX idx_tenant_owner_email ON tenant (LOWER(owner_email));

CREATE TABLE platform_admin (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    email        VARCHAR(150) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_date TIMESTAMP
);

CREATE UNIQUE INDEX idx_platform_admin_email ON platform_admin (LOWER(email));

CREATE TABLE agent (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(36) NOT NULL,
    name         VARCHAR(150) NOT NULL,
    email        VARCHAR(150) NOT NULL,
    phone        VARCHAR(20),
    department   VARCHAR(30) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_date TIMESTAMP
);

CREATE INDEX idx_agent_tenant ON agent (tenant_id);
CREATE UNIQUE INDEX idx_agent_email ON agent (LOWER(email));

-- Resolves an unauthenticated public-proposal request to the tenant schema that owns
-- it, per the blueprint's cross-tenant REQUIRES_NEW pattern (§3.5). Manual tenant_id
-- filtering is correct here, unlike tenant-schema tables - this row IS the exception.
CREATE TABLE proposal_link (
    token        VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(36) NOT NULL,
    lead_id      VARCHAR(36) NOT NULL,
    created_date TIMESTAMP,
    expires_at   TIMESTAMP
);

CREATE INDEX idx_proposal_link_tenant ON proposal_link (tenant_id);
