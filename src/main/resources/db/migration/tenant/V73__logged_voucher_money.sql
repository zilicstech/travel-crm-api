-- A booking's money is the sum of its vouchers. Bookings logged before this kept their money on
-- the booking row and saved their one voucher at zero; move it onto that voucher, with the
-- supplier bill it drafted. A price logged without a markup mode becomes a flat markup.
WITH logged AS (
    SELECT b.id AS booking_id, MIN(d.id) AS document_id
    FROM booking b
    JOIN booking_document d ON d.booking_id = b.id AND d.deleted_at IS NULL
    WHERE b.deleted_at IS NULL AND b.selling_price > 0
    GROUP BY b.id
    HAVING COUNT(*) = 1 AND SUM(d.net_cost) = 0 AND SUM(d.selling_price) = 0
),
bills AS (
    SELECT s.booking_id, MIN(s.id) AS bill_id
    FROM supplier_invoice s
    WHERE s.deleted_at IS NULL AND s.status <> 'CANCELLED'
    GROUP BY s.booking_id
    HAVING COUNT(*) = 1
)
UPDATE booking_document d SET
    net_cost = b.net_cost,
    selling_price = b.selling_price,
    markup_mode = COALESCE(b.markup_mode, 'FLAT'),
    markup_value = CASE WHEN b.markup_mode IS NULL THEN b.selling_price - b.net_cost ELSE b.markup_value END,
    vendor_id = COALESCE(d.vendor_id, b.vendor_id),
    supplier_invoice_id = COALESCE(d.supplier_invoice_id, bills.bill_id)
FROM logged
JOIN booking b ON b.id = logged.booking_id
LEFT JOIN bills ON bills.booking_id = logged.booking_id
WHERE d.id = logged.document_id;
