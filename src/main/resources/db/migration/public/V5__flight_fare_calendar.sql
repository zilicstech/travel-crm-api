-- Flight fare calendar (2026-11-18, owner rulings): the cheapest bookable published fare per adult the last one-way,
-- adults-only, regular-fare search found for a route, cabin and day. Shared by every agency (no tenant, no personal
-- data); each agency is shown it after its own discount when read. Overwritten by the next such search.
CREATE TABLE public.flight_fare_calendar (
    origin          VARCHAR(3)    NOT NULL,
    destination     VARCHAR(3)    NOT NULL,
    cabin           VARCHAR(20)   NOT NULL,
    depart_date     DATE          NOT NULL,
    fare_per_adult  NUMERIC(12,2) NOT NULL,
    searched_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (origin, destination, cabin, depart_date)
);
