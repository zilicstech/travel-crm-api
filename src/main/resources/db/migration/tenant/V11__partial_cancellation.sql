-- Some passengers of a flight cancelled, the booking kept for the rest (client 27 Nov). When it last happened; what was
-- cancelled is in the booking's activity and on its ticket (the travellers marked cancelled).
ALTER TABLE booking ADD COLUMN partly_cancelled_at timestamp without time zone;
