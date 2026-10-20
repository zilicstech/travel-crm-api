-- The accountant's own short note on a booking (e.g. "Done (Zohaib)"), shown on the Bookings list.
ALTER TABLE booking ADD COLUMN accountant_remark VARCHAR(255);
