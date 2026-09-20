-- Adds the country alongside an existing hotel city, so the hotel/transfer city picker can
-- show "Phuket (Thailand)" instead of a bare city name. Purely additive and nullable - no
-- backfill. hotel_city keeps holding exactly what it holds today (e.g. "Phuket"); the country
-- name/code convention for visa fields is unchanged, since those columns already store a full
-- country name and need no migration at all.
ALTER TABLE lead_service ADD COLUMN hotel_country_code VARCHAR(2);
ALTER TABLE booking      ADD COLUMN hotel_country_code VARCHAR(2);
