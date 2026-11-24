# Booking extras (2026-11-25): misc sub-category, extra vouchers, assigned by, PNR column

Owner request 2026-11-25, items 1, 2, 4 and 5. Item 3 (flight reissue) waits for the owner's money ruling and is
designed separately.

## 1. Miscellaneous sub-category

- New `AgencySettingKind.MISC_CATEGORY` (no service type), managed in Settings > "Miscellaneous sub-categories" like
  the lead sources (add, rename, switch off, delete). Nothing seeded.
- Tenant `V7__booking_extras.sql`: `lead_service.misc_category` and `booking.misc_category`, varchar(60), nullable.
- Stored as plain text (not a setting id), so renaming or deleting an option never rewrites old records; the server
  checks only the length (1-60, trimmed, blank = none) and keeps it only on Miscellaneous services and bookings.
- Picked on a lead's Miscellaneous service (Service details) and on New Booking for a Miscellaneous booking; copied
  from the service to the booking Log Booking writes. A value no longer in Settings stays shown and selectable on the
  record that has it.
- Shown on the lead's service card, the booking detail and the bookings list ("Miscellaneous · Insurance"), and in
  the booking's audit history.

## 2. Extra vouchers later, on every CRM booking

Already possible on a booking the agency logged (Add voucher, any type, including Ancillary, until it is cancelled;
the price, supplier bill and "added since the last invoice" all follow). New: a **Voyra-booked** booking takes extra
vouchers too.

- Allowed once the mirror is CONFIRMED or COMPLETED (Pending: 409 `WAITING_FOR_VOYRA`), types Ancillary, Insurance
  and Other only (Voyra's own ticket or hotel voucher stays the platform's and stays locked). Edit, file, charges and
  delete of such an extra voucher work as on any booking; Voyra's voucher still answers 409 `MANAGED_FROM_PLATFORM`.
- The extra voucher has its own supplier (the one-supplier-per-booking rule does not apply to it on a Voyra
  booking); "Voyra Platform" is refused as its supplier. The booking's supplier stays Voyra Platform and its markup
  fields stay Voyra's.
- Voyra's money paths stay on Voyra's own bill: a partial cancellation credits the Voyra vendor's bill, the wallet
  refund on a Voyra cancel posts only to the Voyra vendor's credit notes, and on a Voyra cancel the extra vouchers'
  bills are kept by their suppliers (their cost is added to the supplier charge, and so to what the client keeps
  paying; add-ons bought outside are not refundable through Voyra) and are placed before Voyra's bill so Voyra's
  bill carries exactly Voyra's refund.

## 4. Assigned by on New Booking

- `BookingCreateRequest.assignedById` (owner = agency id, or an active staff member), same check and snapshot as the
  issue details (`assignedById/assignedByName`). New Booking shows an optional "Assigned by" picker (owner and every
  active agent). Changeable later on the Issue details card as today.

## 5. PNR column on the bookings list

- Frontend only: a "PNR / Ref" column after Booking no. listing the booking's reference plus each voucher's
  reference, distinct, comma-separated. Remarks keeps the notes only.

## Not in this round

Flight reissue (item 3); a filter on the sub-category; the sub-category on invoices.
