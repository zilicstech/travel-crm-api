-- A short, per-agency booking number (shown as BK-00042) that people can quote, search and
-- sort by. Each tenant has its own schema, so this sequence is already per agency.
CREATE SEQUENCE IF NOT EXISTS booking_number_seq;

ALTER TABLE booking ADD COLUMN booking_number BIGINT;

-- Number the existing bookings in the order they were made.
UPDATE booking b
SET booking_number = n.rn
FROM (SELECT id, row_number() OVER (ORDER BY created_date NULLS FIRST, id) AS rn FROM booking) n
WHERE b.id = n.id;

SELECT setval('booking_number_seq', COALESCE((SELECT max(booking_number) FROM booking), 0) + 1, false);

ALTER TABLE booking
    ALTER COLUMN booking_number SET DEFAULT nextval('booking_number_seq'),
    ALTER COLUMN booking_number SET NOT NULL,
    ADD CONSTRAINT uq_booking_booking_number UNIQUE (booking_number);
