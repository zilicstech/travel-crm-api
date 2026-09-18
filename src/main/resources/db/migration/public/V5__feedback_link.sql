-- Resolves an unauthenticated public-feedback request to the tenant schema that owns it,
-- same cross-tenant REQUIRES_NEW pattern as proposal_link (blueprint §3.5). Keyed on the
-- booking, not the lead - feedback is requested against one specific completed trip.
CREATE TABLE feedback_link (
    token        VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(36) NOT NULL,
    booking_id   VARCHAR(36) NOT NULL,
    client_id    VARCHAR(36) NOT NULL,
    created_date TIMESTAMP,
    expires_at   TIMESTAMP
);

CREATE INDEX idx_feedback_link_tenant ON feedback_link (tenant_id);
CREATE INDEX idx_feedback_link_booking ON feedback_link (booking_id);
