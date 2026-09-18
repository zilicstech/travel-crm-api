-- "Communication history" today is only free-text lead notes plus the system timeline - no
-- logged calls, WhatsApp messages or emails. Real WhatsApp/email API integration needs external
-- credentials and business verification (out of scope this phase); this is the internal answer:
-- an agent manually logs what happened on a call/WhatsApp/email/meeting, optionally with an
-- attachment (a screenshot of a WhatsApp thread, an email export).
--
-- Scoped to client, not lead, because a conversation is often about the relationship generally
-- ("checking in before the trip") rather than one specific enquiry - lead_id is nullable so a
-- call can still be tied to the lead it was about when there is one.
CREATE TABLE communication_log (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    client_id        VARCHAR(36)  NOT NULL,
    lead_id          VARCHAR(36),
    member_id        VARCHAR(36),
    channel          VARCHAR(20)  NOT NULL,
    direction        VARCHAR(10)  NOT NULL,
    subject          VARCHAR(200),
    summary          VARCHAR(2000) NOT NULL,
    occurred_at      TIMESTAMP    NOT NULL,
    duration_minutes INTEGER,
    outcome          VARCHAR(200),
    file_key         VARCHAR(500),
    file_name        VARCHAR(255),
    content_type     VARCHAR(150),
    actor_id         VARCHAR(36)  NOT NULL,
    actor_name       VARCHAR(150) NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_communication_log_client ON communication_log (client_id, occurred_at DESC);
CREATE INDEX idx_communication_log_lead   ON communication_log (lead_id, occurred_at DESC);
