-- FRD US-ACC-6.1: bookings below this margin % get a low-margin alert badge on the
-- profitability surfaces. Nullable + an application-level default (see BookingService) rather
-- than NOT NULL DEFAULT, so an agency that never visits Settings doesn't silently get a magic
-- number baked into a column - the fallback stays in one place in code, not duplicated in SQL.
ALTER TABLE tenant ADD COLUMN low_margin_threshold_percent NUMERIC(5, 2);
