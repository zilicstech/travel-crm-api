# Client round 27 Nov - plan

Source: the client's "27 CRM ERROR.pdf" (12 points, some in Hindi). Owner rulings of 2026-11-27 are marked **ruling**.

## What the client asked, read point by point

| # | Client's words (translated where Hindi) | What it means |
|---|---|---|
| 1 | Remove credit limit mandate for B2C; keep it mandatory for corporate | Credit limit optional for B2C clients |
| 2 | Cancellations home page needs more information: Sector, PNR, Travel date, Fare issued on, Cancelled on, Vendor | More columns on the Cancellations list |
| 3 | Logging a hotel booking, no option to select passenger names | Guests picker on the hotel voucher |
| 4 | A smooth way to add follow-ups for cancellation | One-click follow-up from the list |
| 5 | A smooth way to capture day-to-day bookings: 3-4 min in the CRM vs 1 min in Excel | One-step booking entry |
| 6 | "insurance ka option separate rahe ... ye sab ho chahiye" = keep Insurance as its own option, with all these fields (type, from country, travelling country, start, end, days, persons) | Insurance as its own service type |
| 7 | "my booking me booking issued and shared in group ho" | **Ruling: skipped** until the client explains |
| 8 | "If we book 2 pax and later cancel 1, there should be an option to cancel 1 pax; now the full ticket cancels. Like below" (Tripjack's pick-passengers screen) | Cancel some passengers of a flight |
| 9 | "When we list a cancellation for follow-up, it should stay there until the refund is processed" | Stay in Open until refund processed |
| 10 | "Issuing a round-trip flight, there's no option to enter the return flight; add it" | Return flight on the e-ticket |
| 11 | "After issuing, if selling is in AED, the sheet should show AED" | Bookings list shows the foreign selling currency |
| 12 | "Remove GST from the flight cancel charge; show customer paid, refund and expected refund from supplier" | **Ruling: remove GST always; show booking-value figures too** |

## Phase A - small fixes (no new screens)

1. **Credit limit (B2C optional).** `ClientService.create`: the "enter the credit limit" rule applies to B2B only; a B2C client
   may be created without one (no limit) by anyone. `AddClientModal`: the field is required (`*`) only for B2B, as today for
   agents. Owner behaviour unchanged (may leave any client open).
2. **Cancellations columns.** `CancellationRowResponse` gains `sector` (flight: from-to of the e-ticket legs or the booking's
   flight fields; other types: destination), `pnr` (booking PNR or first voucher reference), `issuedOn` (the main voucher's issued
   date), `cancelledAt` (already there), `vendorName` (booking supplier). The list shows Booking, Client, Sector, PNR, Travel,
   Issued on, Cancelled on, Vendor, Status, Next follow-up, Refund to client; it scrolls sideways on small screens.
3. **Hotel guests.** The hotel voucher (and transfer voucher) gets the same "Travellers on this voucher" picker as the e-ticket;
   `BookingDocumentService.applyTrip` keeps `travellers` for HOTEL_VOUCHER and TRANSFER_VOUCHER. The list's Pax name already
   reads voucher travellers.
4. **Quick follow-up.** Each Cancellations row gets an "Add follow-up" button opening the existing `FollowUpModal`
   (after 1 day / 2 days / date + note + who) and, when one is open, "Done". No need to open the detail first.
5. **Stays listed until the refund is processed.** New tenant migration `V9`: `booking.refund_processed_at`,
   `refund_processed_by`. A cancelled booking stays in **Open** until someone marks **Refund processed** (owner, agent who can
   open it, accountant; `POST /api/bookings/{id}/refund-processed`, undo with DELETE) or Accounts cross-checks it. Marking a
   follow-up done no longer drops it. History records who and when.
6. **Return flight.** `FlightLegsEditor` gets "Add return flight" next to "Add connecting flight": a leg pre-filled from the last
   arrival back to the first departure, labelled "Return flight". The server already reads a return date from an out-and-back
   ticket (`VoucherTrip.details`), so the trip shows as round trip and boarding passes see both dates.
7. **Selling currency.** When every voucher of a booking was bought in one foreign currency (and no selling currency was set in the
   issue details), the Bookings list's Selling currency shows that currency and Selling price shows it in that currency
   (`AED 1,200`), with the rupee figure under it. The issue-details currency still wins when set.
8. **Cancel window.** **Ruling:** no GST on cancellation charges: `BookingInvoiceIssuer.cancellationChargesTotal` and
   `issueCancellationCharges` stop adding GST (the label becomes "Cancellation charges invoice"). For a booking already invoiced,
   the credit note keeps the retained fee out of the refund as today (its GST was on the original invoice). **Ruling:** the window
   also shows "On the booking's value": booking value (selling price), refund on that value (selling - all charges), and expected
   back from the supplier on the purchase price (purchase - airline - supplier charge). Information only; the books still post
   what was really received and paid.

## Phase B - Insurance as its own service

New `BookingType.INSURANCE` and `ServiceType.INSURANCE` (built like Miscellaneous, 2026-11-20): offered in New Booking and
on a lead. Fields (tenant `V9`, nullable): insurance type (Individual, Family, Student, Senior citizen - **my call**), from country
(default India), travelling countries (several), start date, end date, days (worked out), persons (the travellers). Start and
end reuse the booking's journey and return dates; the countries go in the destination. Its voucher is "Insurance policy"
(policy number as the reference). Invoiced in the Miscellaneous category (no new invoice series). The existing INSURANCE
add-on voucher on Voyra bookings is unchanged.

## Phase C - one-step booking entry

New Booking gets "Already issued? Add the voucher now" (ticked by default): the same window then shows the voucher form
(supplier, reference, issued date, travellers, flights / hotel details, purchase price, markup) and Save creates the booking and
its voucher together, so it lands Confirmed with one save instead of New Booking, then Manage, then Upload voucher. The form
keeps the last client, supplier and service for "Save & add another". Fields are in typing order and Enter moves on.

## Phase D - cancel some passengers of a flight (money design, for approval)

**Status (2026-11-28):** built for bookings not yet invoiced (owner: go ahead, lower the voucher's prices). An invoiced booking is refused (409 `ALREADY_INVOICED`): its credit note stays Accounts' step until the credit-note engine takes partial shares. Instead of a `booking_partial_cancellation` table, tenant `V11` adds `booking.partly_cancelled_at` and the details go in the booking's activity.

**Who and when.** An agency-logged flight booking (not a Voyra booking: those use the Voyra cancel request) that is Confirmed or
Completed, whose e-ticket(s) name at least 2 travellers. The owner or an agent who can open it. Picking every traveller is the
normal full cancel.

**The window.** "Cancel passengers" on the booking page, beside Cancel booking: tick the travellers (Tripjack-style), then:
- their **share**, pre-filled as an equal split of the booking's selling and purchase price (2 of 3 travellers = 2/3), editable
  (**ruling**), never above what is left of the booking;
- the airline charge, supplier fee and agency fee, as on the full cancel; **no GST** on them (**ruling**);
- the same "on the booking's value" figures as the full cancel: share, refund on it after charges, expected back from the supplier.

**What it posts (the booking stays Confirmed for the others):**

| Booking state | Client side | Supplier side |
|---|---|---|
| Invoiced | A credit note on its invoice for **share less every charge** (existing credit note, `cancellationFee` = the rest of the booking's share, so the retained part stays billed) | Bill approved: a supplier credit note for **purchase share less airline and supplier charge** (existing supplier credit note, charges as `retentionFee`). Bill still a draft: its amount drops by that much |
| Not invoiced yet | The e-ticket voucher's selling price drops by **share less every charge**, so the first invoice bills only what is kept | The voucher's purchase price drops by **purchase share less the airline and supplier charge** (the bill follows the voucher) |

Money the client already paid above the new total becomes their credit (as any credit note does today) and Accounts refunds it
from the usual refunds list. Nothing new in the ledger: only existing credit-note and voucher paths.

**What it records.** Tenant `V11`: table `booking_partial_cancellation` (booking, travellers named, selling share, purchase share,
the three charges, credit note and supplier credit note ids, who, when). The cancelled travellers are marked `cancelled` on the
voucher (struck through in Pax name, left out of boarding passes). The Cancellations tab shows the booking as **Partly cancelled**
with its refund, and it stays in Open until **Refund processed** (Phase A).

**Not in D.** Cancelling one sector of a return for everyone; a second partial cancel that would leave nobody (that is a full cancel);
Voyra bookings.

**Open point for the owner:** for a booking **not invoiced yet**, is lowering the voucher's prices (above) right, or should a
partial cancel need the booking invoiced first (simpler books, one more step for the agent)? My pick: lower the voucher's prices.

## Not in this round

Point 7 (issued / shared in group) until the client explains it. Partial cancel of one sector only (passengers only for now).

## Order, tests, release

A, then B, then C, then D (after its design is approved). Each phase: backend `./mvnw -o verify` (100 % coverage, blueprint
audit), frontend vitest at 100 %, tsc, lint, a live check on the local servers with Dummy QA, docs (root `CLAUDE.md`,
`travel-crm/CLAUDE.md`, `PROGRESS.md`). Nothing is committed or pushed until asked. Deploy backend before frontend.
