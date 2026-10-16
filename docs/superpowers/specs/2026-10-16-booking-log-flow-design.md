# Booking log flow - design and plan

Date: 2026-10-16. Status: design approved in chat; implementing. Refund is out of scope (next phase).

## Goal

A CRM user records a booking in the fewest possible inputs, from two places:

1. **From a lead's service** - the booking is filled from the service. The agent supplies only the
   booking reference, the voucher file, net cost and selling price (plus supplier, optional).
2. **Ad-hoc, from the Bookings page** - no lead. A short form: client, type, destination, dates,
   reference, supplier, cost, price, voucher.

Every booking logged this way has proof (a voucher file) and is **CONFIRMED** on creation.

## Decisions (user answers, 2026-10-16)

| Question | Answer |
|---|---|
| Voucher at log time | Required |
| Status on log | CONFIRMED immediately; no separate Confirm click |
| Cost / selling price | Always typed by the agent (quote shown only as a hint) |
| Travellers | All non-dropped lead travellers included automatically; agent may untick |

## Design

### Backend - `POST /api/bookings/log` (multipart)

Parts: `booking` (JSON, `BookingLogRequest`) and `file` (the voucher). Roles: Owner, Agent.

`BookingLogRequest`: `serviceId?`, `leadMemberIds?`, `clientId?`, `agentId?`, `type?`,
`destination?`, `journeyDate?`, `returnDate?`, `internationalTrip?`, `reference*`, `vendorId?`,
`netCost*`, `sellingPrice*`, `notes?`.

`BookingLogService.log(request, file)` (not transactional - blueprint §8.6):

1. Reject an empty file.
2. Pick a document id, store the file (`booking-documents/<docId>`; `UploadValidator` runs inside
   `FileStorageService.store`).
3. Build a `BookingCreateRequest` (`BookingLogRequestMapper`, pure):
   - **With `serviceId`**: client, type, destination and the type's own fields come from the
     service and override anything in the request. Agent = the service's assignee if set, else
     `agentId` (the backend already ignores `agentId` for the AGENT role). Passengers = the lead's
     travellers in `leadMemberIds` (or all non-DROPPED when absent) for Flight/Hotel/Visa; the
     selling price is split evenly across them (remainder on the last) so the invoice, which
     prints one line per passenger fare, still totals the selling price.
   - **Without `serviceId`**: `clientId`, `type`, `destination` are required.
   - `reference` goes to the type's reference column: Flight/Package `pnr`, Hotel
     `hotelConfirmationNo`, Visa `visaApplicationNo`, Transfer `transferVoucherNo`.
4. `BookingService.logBooking(createRequest, docId, file meta, docType)` (`@Transactional`):
   reuses `createBooking` (timeline entry, service -> BOOKED, supplier-bill auto-draft, audit),
   sets status CONFIRMED, saves the `booking_document` row with the stored file key.
5. If step 4 throws, delete the stored file and rethrow.

The old JSON `POST /api/bookings` stays (tests and API clients use it); the UI stops calling it.

### Frontend

- `lib/api/services/bookings.ts`: `logBooking(request, file)`.
- `components/lead/ServiceBookings.tsx`: `LogBookingModal` becomes one small form - read-only
  summary of the service, traveller chips (untick), reference*, voucher*, net cost*, selling
  price* (quoted total as a hint), supplier, agent (owner only, only when the service has no
  assignee). Booking card gets a "View voucher" action; Confirm stays only for legacy Pending rows.
- `components/shared/BookingFormModal.tsx`: ad-hoc form cut to client*, type*, agent* (owner),
  destination*, travel date, return date, supplier, reference*, net cost*, selling price*,
  voucher*. Ticket no., airline and the manual passenger list are removed.
- `agency/bookings` and `agents/bookings`: a Documents action per row opens the existing
  `BookingDocuments` list in a modal (view / add / remove).

### Not in scope

Refund, credit notes, cost components, pricing modal, deadlines, invoicing.

## Plan

1. Backend: `BookingLogRequest` DTO, `BookingLogRequestMapper` + unit test, `BookingService.logBooking`,
   `BookingLogService`, controller endpoint, service test (confirmed, document saved, file cleaned
   up on failure). `mvn verify` green.
2. Frontend API + lead modal + ad-hoc modal + documents action on both bookings pages. `tsc` clean.
3. Local Chrome walkthrough on a local DB (never prod): log from a Flight and a Hotel service, an
   ad-hoc Package booking, view voucher, raise an invoice to confirm passenger split totals.

## Known limits

- Viewing a voucher is still blocked until the booking is fully paid (existing voucher-release
  gate, Owner can override). Agents cannot open their own uploaded proof before payment.
- Hotel bookings from a service have no hotel name (the service has only a city), so the invoice
  narration shows city/dates only.
