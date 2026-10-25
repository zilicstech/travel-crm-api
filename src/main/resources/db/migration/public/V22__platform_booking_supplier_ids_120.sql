-- Supplier review and booking ids up to 120 characters: an 80-character column rejected long supplier ids
-- (the mock's booking id embedded the ~70-character review id). Widening keeps the UNIQUE constraint and the data.
ALTER TABLE platform_booking ALTER COLUMN supplier_review_id TYPE VARCHAR(120);
ALTER TABLE platform_booking ALTER COLUMN supplier_booking_id TYPE VARCHAR(120);
