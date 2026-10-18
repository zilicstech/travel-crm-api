-- A voucher's reference (up to 100 characters) becomes the booking's PNR when it has none; the
-- PNR column was 20, so a longer e-ticket or package reference failed with a data error.
ALTER TABLE booking ALTER COLUMN pnr TYPE VARCHAR(100);
