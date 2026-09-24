-- Backs ACCOUNTING_REDESIGN_SPEC.md §5.1 (invoice body generated from the booking, not hand
-- authored) and closes gaps 1-2 of that spec's §6: a per-booking passenger table and a per-
-- passenger sector list, so a customer invoice can print one row per traveller with their own
-- itinerary legs underneath - exactly what the client's outgoing invoice does. Fare components
-- (Basic/YQ/YR/K3/OC - gap 3) are deliberately out of scope: one fare_amount per passenger.

-- invoice.service_category drives the number series, the printed title and the print template.
-- Nullable: existing invoices predate this column and are never renumbered or reprinted under
-- a new series retroactively.
ALTER TABLE invoice ADD COLUMN service_category VARCHAR(20);

-- Whether a FLIGHT booking's itinerary leaves India - the only signal InvoiceServiceCategory
-- needs to split AIR_INTERNATIONAL from AIR_DOMESTIC. Set by whoever books it; there is no
-- airport-code lookup anywhere in this codebase and this migration does not add one. Defaults
-- to FALSE (domestic) for every existing FLIGHT booking - safe because a wrongly-defaulted
-- flag only affects which series a *future* invoice against that booking draws from, not
-- anything already issued.
ALTER TABLE booking ADD COLUMN is_international BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- BOOKING_PASSENGER - one traveller on one booking, with the fare attributed to them. Flat FK
-- to booking (blueprint §8.4), never a JPA association. lead_member_id is the traveller's row
-- on the owning lead's manifest when the booking came from a lead; passenger_name is a
-- snapshot so invoice printing needs no join and survives the traveller later being renamed or
-- dropped from the lead. Order on the printed invoice is sort_order, matching entry order, not
-- alphabetical - mirrors the source system's numbered passenger blocks (001, 002, ...).
-- ---------------------------------------------------------------------------
CREATE TABLE booking_passenger (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    booking_id       VARCHAR(36) NOT NULL,
    lead_member_id   VARCHAR(36),
    passenger_name   VARCHAR(150) NOT NULL,
    pax_type         VARCHAR(10),
    fare_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMP,
    created_by       VARCHAR(36)
);
CREATE INDEX idx_booking_passenger_booking ON booking_passenger (booking_id);

-- ---------------------------------------------------------------------------
-- BOOKING_SECTOR - one itinerary leg under one booking_passenger. Only populated for
-- sector-wise categories (air, rail - see InvoiceServiceCategory.isSectorWise); a hotel or
-- visa passenger row has none. The fare is never repeated here - it lives once on the parent
-- booking_passenger, exactly as the source system states a fare once per passenger block
-- regardless of leg count.
-- ---------------------------------------------------------------------------
CREATE TABLE booking_sector (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    booking_passenger_id  VARCHAR(36) NOT NULL,
    sort_order            INTEGER NOT NULL DEFAULT 0,
    sector_from            VARCHAR(20),
    sector_to              VARCHAR(20),
    flight_number         VARCHAR(20),
    travel_date           DATE,
    cabin_class           VARCHAR(20),
    created_at            TIMESTAMP
);
CREATE INDEX idx_booking_sector_passenger ON booking_sector (booking_passenger_id);
