-- Outbound emails, written after the business transaction commits and sent by NotificationOutboxJob.
-- One row per recipient. Bodies are cleared once sent (they can hold an application resume link).
CREATE TABLE notification_outbox (
    id              VARCHAR(36)  PRIMARY KEY,
    kind            VARCHAR(40)  NOT NULL,
    recipient       VARCHAR(150) NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    body_html       TEXT,
    body_text       TEXT,
    status          VARCHAR(10)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_notification_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'))
);

CREATE INDEX ix_notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
