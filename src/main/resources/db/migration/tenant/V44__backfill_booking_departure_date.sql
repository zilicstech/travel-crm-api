-- Backfill for V43: departure_date only populates on write (BookingService#deriveDepartureDate,
-- called from createBooking/updateBooking), so any booking row that existed before V43 and has
-- not been touched since keeps departure_date NULL. A NULL departure_date recognises revenue
-- immediately (Rule 2.2.2 - never blocked), which is wrong for a pre-existing flight booking
-- with a genuinely future journey_date. One-time, idempotent (WHERE departure_date IS NULL),
-- mirrors BookingService#deriveDepartureDate's exact precedence: journey_date, then
-- hotel_check_in, then transfer_date.

UPDATE booking
SET departure_date = COALESCE(journey_date, hotel_check_in, transfer_date)
WHERE departure_date IS NULL;
