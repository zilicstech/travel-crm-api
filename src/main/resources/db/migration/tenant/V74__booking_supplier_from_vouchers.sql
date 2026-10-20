-- A booking created without a supplier takes its vouchers' supplier when they all share one, so it
-- can be invoiced with other bookings of that supplier (applyVoucher now does this going forward).
WITH single AS (
    SELECT d.booking_id, MIN(d.vendor_id) AS vendor_id
    FROM booking_document d
    WHERE d.deleted_at IS NULL AND d.vendor_id IS NOT NULL
    GROUP BY d.booking_id
    HAVING COUNT(DISTINCT d.vendor_id) = 1
)
UPDATE booking b SET vendor_id = v.id, supplier = v.name
FROM single
JOIN vendor v ON v.id = single.vendor_id
WHERE b.id = single.booking_id AND b.vendor_id IS NULL;
