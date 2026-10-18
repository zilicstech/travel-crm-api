# Voucher-level details, voucher lock, Ledger menu – Plan

**Branch:** `feat/soft-delete-and-accounts` (both repos)

**Asked by the user, 2026-10-18 (5 points).** The user answered:

- **Point 4.** Each voucher carries its full travel details.
- **Point 5.** Drop the Money In tools. Do not move them.
- **Point 3.** Lock only the vouchers that are already invoiced.

## 1. Client credit limit wherever a booking or lead starts
**Today:**
- New Booking (Bookings list) and Quick book already show `ClientCreditChip` once a client is picked. This was checked live: "Available ₹X of ₹Y".
- Add Lead is missing it.

**Change:** Add Lead shows the chip in the "Selected Client" card. A client created inline shows it after creation too.

## 2. Price on Edit details
`BookingDetailsModal` gets a read-only block showing "Supplier cost ₹X · Selling price ₹Y" with the line "comes from the vouchers – edit a voucher to change it". The price is never typed (decided 2026-10-18).

The modal now edits only the trip basics plus the default supplier for new vouchers. References and timings move to the vouchers (point 4). It uses `BookingServiceFields` in `create` mode. The action hint becomes "Trip basics and default supplier".

## 3. Invoiced vouchers can't be edited or deleted
**When a voucher counts as invoiced:** its `createdAt` is on or before the `issuedAt` of the booking's latest live (non-draft, non-cancelled) tax invoice. A voucher added after the invoice stays editable until its extra invoice is generated.

**Server:**
- `updateDocument` and `deleteDocument` refuse with 409 "This voucher is already invoiced".
- Attaching a file is still allowed.
- `BookingDocumentResponse.invoiced` (boolean) is computed.

**Screen:** the pencil and trash icons are hidden when `invoiced` is set.

## 4. Travel details at voucher level; vouchers at the top
**Data:** `booking_document.details JSONB` (tenant V58), a `Map<String,String>` of the same flat keys `BookingServiceFields` already uses. The server keeps only the keys allowed for the booking's type and drops blank values. The allowed keys are:

| Type | Keys |
|---|---|
| FLIGHT | airline, flightNumber, flightFrom, flightTo, journeyDate, returnDate, flightDepartTime, flightArriveTime, flightArriveDate, flightCabin |
| HOTEL | hotelName, hotelCity, hotelCheckIn, hotelCheckOut, hotelRooms, hotelRoomType, hotelBoardBasis |
| VISA | visaCountry, journeyDate, visaAppointmentDate, visaAppliedDate, visaIssuedDate |
| TRANSFER | transferPickup, transferDropoff, transferDate, transferTime, transferVehicleType |
| PACKAGE and others | destination, journeyDate, returnDate |

The reference stays `referenceNumber` (PNR / confirmation number). Supplier, cost and markup are unchanged.

**Voucher form:**
- For a travel document type (e-ticket, hotel voucher, visa copy, transfer voucher), it shows the booking type's travel fields through a new `voucher` mode of `BookingServiceFields`: no `*` marks, and no booking-reference fields.
- A new voucher's details start prefilled from the booking's basics. Edit prefills from the voucher.

**Voucher row:** becomes a card with three lines:
1. Type · reference · key detail (airline and flight no. / hotel / country / pickup).
2. Route or stay with dates and times.
3. Supplier name (from the vendor store) · cost · price.

The edit, delete and attach icons follow point 3.

**Manage page:**
- **Order:** header, then the Vouchers card (with Add document), then money metrics, then the Booking management steps (the VOUCHER step keeps its state and summary but no longer embeds the list), then Money record.
- The single "Booking details" card is removed.
- A compact "Trip" strip keeps what still belongs to the booking: travel dates, agent, booked on, deadlines and travellers.

**Completion job:** the trip's end date is the latest of the booking's own end date and every live voucher's date keys (`journeyDate`, `returnDate`, `hotelCheckOut`, `transferDate`, `flightArriveDate`). This way a return flight held on its own voucher doesn't complete the booking early.

## 5. Ledger menu replaces Money In and Customers
**Backend:** `GET /api/accounts/ledger/entries?partyType=CUSTOMER|SUPPLIER|ALL&partyId=&from=&to=` (Owner/Accountant) returns `PartyLedgerResponse`:
- `partyType`, `partyId`, `partyName`
- `from`, `to`
- `openingBalanceInr` and `closingBalanceInr` (single party only)
- `totalDebitInr`, `totalCreditInr`
- `entries[{partyType, partyId, partyName, entryDate, entryType, documentNumber, narration, bookingId, bookingRef, debitInr, creditInr, runningBalanceInr}]`

How it is built:
- **One party:** the existing `CustomerLedgerService.statement` / `SupplierLedgerService.statement` are mapped. Balance sign: customer = Dr − Cr (receivable), supplier = Cr − Dr (payable).
- **ALL, or a whole party type:** new date-ranged repository queries, with party names and booking refs looked up in batches. There is no running balance, only totals.

**Frontend:** new `app/accounts/ledger/page.tsx`.
- **Filters:** party type (All / Customers / Suppliers), a party picker (ClientPicker / VendorPicker), and from/to dates (default: this financial year).
- **Table:** Date · Party · Document · Narration · Booking (link) · Debit · Credit · Balance.
- **One party picked:** opening and closing balances, the credit limit and available amount for a customer, and PDF/Excel export through the existing statement endpoints.

**Nav:** "Ledger" replaces Money In and Customers. The dashboard's "Record receipt" link goes to Invoices.

**Deleted:** `app/accounts/money-in`, `customers`, `receipts` and `credit-notes` pages (the user said to drop them). Their API service functions that nothing uses any more are deleted too.

## Verification
- `mvn verify`, `tsc`, `next build`, and lint with no new errors.
- Unit tests:
  - the voucher details whitelist;
  - the invoiced lock (edit and delete refused, a voucher added later allowed);
  - the completion end date taken from vouchers;
  - the ledger in single-party and all-party modes.
- e2e:
  - `full-journeys` (updated);
  - a new voucher-details spec: add a voucher with flight details, see the card at the top, locked once invoiced;
  - a ledger spec: filter by customer and dates;
  - `shell-and-guards` nav list updated.
- Then the user's full regression through the `full-regression` skill.
