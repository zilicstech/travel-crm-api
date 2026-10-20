-- An air ticket voucher names its travellers and its flights (one or more connecting legs); the
-- invoice line snapshots both, plus the voucher's purchase currency and rate, when it is drafted.
ALTER TABLE booking_document ADD COLUMN travellers jsonb;
ALTER TABLE booking_document ADD COLUMN legs jsonb;

ALTER TABLE invoice_line_item ADD COLUMN travellers jsonb;
ALTER TABLE invoice_line_item ADD COLUMN legs jsonb;
ALTER TABLE invoice_line_item ADD COLUMN purchase_currency VARCHAR(3);
ALTER TABLE invoice_line_item ADD COLUMN purchase_rate NUMERIC(18, 6);

-- Existing e-tickets on flight bookings: their one flight, from the flat details they already carry.
-- Travellers stay empty - the voucher's next edit asks for them, and an invoice won't issue without them.
UPDATE booking_document d
   SET legs = jsonb_build_array(jsonb_strip_nulls(jsonb_build_object(
           'from', upper(d.details ->> 'flightFrom'),
           'to', upper(d.details ->> 'flightTo'),
           'airline', d.details ->> 'airline',
           'flightNumber', d.details ->> 'flightNumber',
           'cabin', d.details ->> 'flightCabin',
           'travelDate', d.details ->> 'journeyDate',
           'departTime', d.details ->> 'flightDepartTime',
           'arriveTime', d.details ->> 'flightArriveTime')))
  FROM booking b
 WHERE b.id = d.booking_id
   AND b.type = 'FLIGHT'
   AND d.doc_type = 'E_TICKET'
   AND d.details ->> 'flightFrom' IS NOT NULL
   AND d.details ->> 'flightTo' IS NOT NULL
   AND d.details ->> 'journeyDate' ~ '^\d{4}-\d{2}-\d{2}$';
