-- Cancellations tab (client round 2026-11-22, owner rulings). New tables only.
-- A follow-up on a booking's cancellation, or a cancellation scheduled for a later date ("cancel after 2 days").
-- Completed, never deleted: what was promised and whether it was kept is the record.
CREATE TABLE cancellation_follow_up (
    id                  varchar(36)  PRIMARY KEY,
    booking_id          varchar(36)  NOT NULL REFERENCES booking (id),
    kind                varchar(20)  NOT NULL CHECK (kind IN ('SCHEDULED_CANCEL', 'FOLLOW_UP')),
    due_date            date         NOT NULL,
    note                varchar(500),
    assigned_agent_id   varchar(36)  NOT NULL,
    assigned_agent_name varchar(150) NOT NULL,
    status              varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'DONE')),
    created_by          varchar(36)  NOT NULL,
    created_by_name     varchar(150) NOT NULL,
    created_at          timestamp    NOT NULL,
    done_by             varchar(36),
    done_by_name        varchar(150),
    done_at             timestamp
);
CREATE INDEX idx_cancellation_follow_up_booking ON cancellation_follow_up (booking_id);
CREATE INDEX idx_cancellation_follow_up_open ON cancellation_follow_up (status, due_date);

-- What people did on a booking, readable by everyone who may open it (the audit log is the owner's).
CREATE TABLE booking_activity (
    id         varchar(36)  PRIMARY KEY,
    booking_id varchar(36)  NOT NULL REFERENCES booking (id),
    kind       varchar(40)  NOT NULL,
    text       varchar(500) NOT NULL,
    by_id      varchar(36)  NOT NULL,
    by_name    varchar(150) NOT NULL,
    at         timestamp    NOT NULL
);
CREATE INDEX idx_booking_activity_booking ON booking_activity (booking_id, at);
