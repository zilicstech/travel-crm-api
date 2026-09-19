-- Accountant is a new login persona but shares the agent row's whole lifecycle (tenant_id
-- scoping, the AES-reversible password the owner credential-reveal flow needs, is_active
-- liveness, unique email) - see UserType.ACCOUNTANT. Reusing this table instead of a new
-- one keeps AuthorResolver and every *_agent_id author-stamped column resolvable through
-- agentRepository, unchanged.
ALTER TABLE agent ADD COLUMN user_role VARCHAR(20) NOT NULL DEFAULT 'AGENT';

CREATE INDEX idx_agent_tenant_role ON agent (tenant_id, user_role);
