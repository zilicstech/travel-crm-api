-- Boarding passes for flights (client round 2026-11-22, owner rulings). New table only.
-- One row per flight booking and travel date once someone has acted: GENERATED (an optional proof file) or NOT_NEEDED
-- (an optional reason). No row means still to do. A row whose date is no longer one of the booking's flight dates is ignored.
CREATE TABLE boarding_pass (
    id              varchar(36)  PRIMARY KEY,
    booking_id      varchar(36)  NOT NULL REFERENCES booking (id),
    travel_date     date         NOT NULL,
    status          varchar(12)  NOT NULL CHECK (status IN ('GENERATED', 'NOT_NEEDED')),
    reason          varchar(300),
    file_key        varchar(500),
    file_name       varchar(255),
    file_type       varchar(100),
    updated_by      varchar(36)  NOT NULL,
    updated_by_name varchar(150) NOT NULL,
    updated_at      timestamp    NOT NULL,
    CONSTRAINT uq_boarding_pass_booking_date UNIQUE (booking_id, travel_date)
);
CREATE INDEX idx_boarding_pass_date ON boarding_pass (travel_date);
