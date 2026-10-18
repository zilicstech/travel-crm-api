-- Each voucher carries its own travel details (a round trip is two e-tickets, each with its own
-- PNR, flight and times), as a flat map of the same keys the booking's own fields use.
ALTER TABLE booking_document ADD COLUMN IF NOT EXISTS details JSONB;
