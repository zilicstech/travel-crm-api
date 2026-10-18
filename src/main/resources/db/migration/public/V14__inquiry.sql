-- Sales inquiries from the unauthenticated marketing site (POST /api/public/inquiries),
-- read by the Platform Admin. Public schema: they arrive before any agency/tenant exists.
CREATE TABLE inquiry (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    source     VARCHAR(40)  NOT NULL,
    name       VARCHAR(150),
    email      VARCHAR(150),
    phone      VARCHAR(30),
    company    VARCHAR(200),
    plan       VARCHAR(60),
    message    VARCHAR(2000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_inquiry_contact CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE INDEX idx_inquiry_created_at ON inquiry (created_at DESC);
