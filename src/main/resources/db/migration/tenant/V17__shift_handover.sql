-- A note per shift, not rostering/clock-in: an outgoing agent (or the owner) leaves a
-- summary plus a pinned lead/booking id list for whoever picks up next. A null to_agent_id
-- means the whole team - visible to every agent's unacknowledged banner, not just one.
CREATE TABLE shift_handover (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    from_agent_id      VARCHAR(36)  NOT NULL,
    from_agent_name    VARCHAR(150) NOT NULL,
    to_agent_id        VARCHAR(36),
    to_agent_name      VARCHAR(150),
    summary            VARCHAR(2000) NOT NULL,
    pinned_lead_ids    TEXT[],
    pinned_booking_ids TEXT[],
    shift_ended_at     TIMESTAMP    NOT NULL,
    acknowledged_at    TIMESTAMP,
    acknowledged_by    VARCHAR(36),
    acknowledged_by_name VARCHAR(150),
    created_at         TIMESTAMP    NOT NULL
);

CREATE INDEX idx_shift_handover_to_agent ON shift_handover (to_agent_id, shift_ended_at DESC);
CREATE INDEX idx_shift_handover_unacknowledged ON shift_handover (acknowledged_at) WHERE acknowledged_at IS NULL;
