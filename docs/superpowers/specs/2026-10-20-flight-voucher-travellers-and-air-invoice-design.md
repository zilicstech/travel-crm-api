# Flight voucher travellers, legs, and the ePrompt-style air invoice

Date: 2026-10-20. Branch: `feat/soft-delete-and-accounts` (both repos). Status: draft for approval.
Reviewed by a builder check and an adversarial review (bmad-review); accepted fixes are folded in and marked
**(review)**.

## Intent

The client (Skytourwayz, Bhopal) wants our system to produce the same air ticket invoice they print from
ePrompt today (`~/Downloads/Demo Invoice.pdf`). That invoice lists, per PNR, every passenger on the ticket
and every flight leg. Today a voucher has neither its travellers nor more than one leg, so the bill can't be
built. The agent must therefore record the travellers and legs on each air ticket voucher; the accountant keeps
entering the fare breakdown (already built); the PDF is redesigned to match the sample; invoice and booking
numbers move to the ePrompt style.

## Goals

1. An air ticket voucher cannot be saved without at least one traveller and one leg.
2. The agent picks travellers from the client's members, or adds a new person inline; the new person is saved
   to the client's member list immediately.
3. An air ticket voucher holds one or more legs (connecting flights on one PNR). A return trip is two vouchers.
4. The tax invoice PDF for air categories matches the sample layout (below).
5. GST on the markup only, shown as CGST 9 % + SGST 9 % rows (IGST 18 % inter-state), added to the total; the
   markup itself is not shown separately. **(review)** The tax maths already exists (`resolveTaxableBase`,
   CGST/SGST/IGST split in `InvoiceDocumentService`); this goal is rendering only.
6. Invoice numbers become `<series prefix><8 digits>` (e.g. `ITI00000001`), one running counter per series, no
   financial-year reset. Booking numbers become `BK<8 digits>` (`BK00000042`).

## Non-goals

- Per-passenger fare breakdown. One breakdown per voucher (the user chose this explicitly).
- Travellers on hotel / visa / transfer / insurance vouchers.
- Changing receipts, journals, supplier payments numbering.
- An "accounts copy" or Original/Duplicate prints.
- Re-rendering already issued invoices in the new layout.

## Anti-goals (failure even if it works)

- A traveller added inline that does not appear on the client's member list.
- An invoice whose rows do not add up to its Gross Total.
- A number gap or duplicate in an invoice series — including a duplicate of a number the agency already used
  in ePrompt under the same GSTIN.
- Breaking the existing combined-invoice, credit-note, cancellation and multi-currency flows.

## Decisions taken with the user

| Topic | Decision |
|---|---|
| Who enters travellers | The agent, on the voucher form; required |
| Source of travellers | Client's existing members (multi-select) or "Add new traveller" saved to the client's members |
| Legs | One voucher = one direction, 1..n connecting legs; return = two vouchers |
| Invoice row | One row per voucher (PNR): all passenger names listed, legs listed, one Basic / YQ / Other / Total |
| Markup on invoice | Folded into the voucher's total; not a visible line |
| GST | 18 % on markup only; CGST 9 % + SGST 9 % rows with amounts, added to the total |
| Invoice number | `ITI00000001` style, per series, no year |
| Booking number | `BK00000001` style, 8 digits |

## Calls I made (override any)

- **Which vouchers (review):** the rule covers `E_TICKET` vouchers on FLIGHT bookings. Other voucher types on a
  flight booking (insurance, other) need no travellers or legs and print as a plain row (description in the
  Passenger column, amounts, no sector).
- **Other column = line taxable − Basic − YQ (review)**, taken from the line's frozen `charges` snapshot and
  taxable value, so markup, discounts and any rounding land there and every row adds up; Round Off prints as
  its own row when non-zero. Never computed from live voucher fields.
- **IGST 18 % when the client's state differs** from the agency's — existing behaviour.
- **Traveller is a snapshot on the voucher** (member id + name + pax type), not re-synced when the member is
  renamed: the name on a ticket is fixed at issue. Pax type from DOB against the first leg's date
  (`PaxTypeCalculator`); no DOB → Adult.
- **Inline new traveller fields:** name (required), relation (default `OTHER`), date of birth, gender.
- **Same traveller on two vouchers of one booking is allowed** (outbound + return).
- **Log Booking (review):** no new UI. The modal already ticks the lead's travellers; the server copies their
  member ids onto the logged voucher and the service's flight sectors into its legs. Not enforced there - the
  issue check catches a gap, and the next edit of the voucher requires them.
- **Credit notes also switch** to `<prefix><8 digits>`.
- **PAN is derived from the GSTIN** (characters 3–12) **(review)** — no new column. The agency profile gains
  phone, email and website (blank ones are omitted from the PDF).
- **Booking-level passengers (`booking_passenger` / `booking_sector`) stay** in the schema and booking form but
  no longer feed FLIGHT invoice lines or descriptions; the voucher is the source. Other passenger-wise
  categories keep today's split **(review)**.
- **A drafted voucher stays locked, as today (review, smaller option).** Only vouchers saved before this change
  can lack travellers; the issue refusal says "delete this draft first, then draft it again". Allowing
  traveller edits on a drafted voucher is a later option.
- **Completion dates keep working (builder):** saving legs also writes `details.journeyDate` (first leg) and the
  last leg's date into `details`, so `BookingCompletionService` / `VoucherDetails.lastDate` are unchanged.

## Data

### Voucher (`booking_document`), tenant migration `V76__voucher_travellers_and_legs.sql`

- `travellers jsonb` — `[{memberId, name, paxType}]`, ordered.
- `legs jsonb` — `[{from, to, airline, flightNumber, cabin, travelDate, departTime?, arriveTime?}]`, ordered.
- Backfill **(review)**: only `E_TICKET` vouchers whose booking is `FLIGHT`; `legs` = one leg from `details`
  (flightFrom, flightTo, airline, flightNumber, flightCabin, journeyDate, times), with `journeyDate` taken only
  when it matches `YYYY-MM-DD`. `travellers` stays null. Issued invoice lines are not backfilled.

### Invoice line (`invoice_line_item`), same migration

- `travellers jsonb`, `legs jsonb`, `purchase_currency`, `purchase_rate` **(review)** — snapshots copied from the
  voucher whenever the draft line is (re)derived, frozen at issue. The PDF renders only from the line.

### Agency (`public.tenant`), public migration

- `contact_phone`, `contact_email`, `website` (nullable).

### Numbers

- **(review)** Invoice numbers come from 13 `DocumentKind`s (`forInvoiceCategory`) plus credit notes. All of
  those switch: counter keyed on `financial_year = 'ALL'`, formatted `"%s%08d"` with the series prefix. Other
  kinds unchanged. Concurrency and no-gap are unchanged (`UPDATE … RETURNING` + `ON CONFLICT` seed).
- Starting value per series: see the open question below.
- Booking ref: `String.format("BK%08d", n)`. Search already accepts `BK42` / `BK00000042`; the 5-digit padding in
  `BookingSpecifications` (prefix match) moves to 8 **(review)**.

## Backend behaviour

- Voucher create/update requests gain `travellers: [{memberId}]` and `legs: [...]`. For an `E_TICKET` voucher
  on a FLIGHT booking: ≥1 traveller, ≥1 leg with from, to, travel date; every memberId must belong to the
  booking's client and be active. The server fills `name` / `paxType`. Same on `logBooking`.
- `InvoiceDocumentService.issue`: besides the fare-breakdown check, refuse an `E_TICKET` line with no travellers:
  "Add the travellers for PNR X on booking BK… (you can do it while the draft is open)".
- `InvoiceLineSource`: for FLIGHT bookings, one line per voucher (no passenger-wise split), with `travellers`,
  `legs`, `charges`, purchase currency and rate snapshots; description "Air ticket PNR X".

## Frontend behaviour

- `DocumentForm` (`E_TICKET` on FLIGHT), after Supplier / Type·Reference·Issued Date:
  - **Travellers** — chip multi-select from the client's members; "+ Add new traveller" opens an inline mini-form
    (name, relation, DOB, gender) → `POST /api/clients/{id}/members` → added to the chips and the client's list.
  - **Flight legs** — repeatable rows (From, To, Airline, Flight no., Cabin, Date, depart/arrive time) with
    "+ Add connecting flight"; first row prefilled from the booking's route/date.
  - Save disabled until ≥1 traveller and a complete first leg.
- Voucher card shows travellers (names, pax type) and legs.
- Settings → Agency profile: phone, email, website.

## PDF (air categories, only when every line carries a legs snapshot — review)

Otherwise (cancellation-charges invoices, "Services on BK…" remainder lines, already issued invoices) the
current layout is kept, with the new number format and header fields.

- **Header:** logo; legal name; address, state + state code, country; phone, email, website; PAN (from GSTIN);
  GSTIN.
- **Right box:** title ("International Air Ticket Invoice" / "Domestic Air Ticket Invoice"), number, date,
  "Airline PNR (A, B)".
- **To:** client name, city, state + code, GSTIN when present. "Place of Supply: State (code)". "Airline: X"
  (distinct airlines from the legs).
- **Table:** Passenger | Sector | Flight | Travel On | Airline PNR | Basic | YQ | Other | Total. Per voucher:
  passenger names numbered `001 NAME` one per line (upper case); one sub-row per leg (`FROM TO CABIN`, flight,
  `dd/MM/yy`, PNR); amounts on the first sub-row only. Non-ticket lines: description, amounts.
- **Footer:** "(Reimbursement of air ticket issued by airlines) Gross Total"; CGST 9 % / SGST 9 % (or IGST 18 %)
  rows; Round Off when non-zero; "Total Amount in ₹" with amount in words in rupees. When every line shares one
  foreign purchase currency and rate **(review)**: also "Total Amount in AED" = ₹ total ÷ that rate, with words
  in that currency (`AmountInWords` already has the minor units). Bank block; terms; "E. & O. E."; "for
  AGENCY"; Receiver's Signature / Authorized Signatory; "(Prepared by: <issuer name>)", computer-generated note,
  "(ID: <invoice short id>)".

## Tests that must change (review)

- `combined-invoice.spec.ts` (number regex), `e2e/fixtures.ts` voucher helpers and every spec that creates a
  flight voucher (multi-currency, voucher-charges, full-journeys, booking-lifecycle, …) — they must send
  travellers and legs.
- `DocumentNumberServiceIT`, `InvoicePdfRendererTest`, `CreditNotePdfRendererTest`, `InvoiceDocumentServiceTest`,
  `BookingSortTest`.

## Left to the builder

- Exact jsonb shape names, DTO field names, component split, PDF column widths and fonts; whether legs reuse
  `FlightSectorDto`.
- New tests, provided they cover: traveller/leg validation and member ownership; the issue refusal; draft-time
  traveller edit refreshing the line; number format and no-gap per series; booking ref; a PDF renderer test for a
  two-leg, two-passenger voucher (rows sum to Gross Total); Playwright: agent adds a flight voucher with an
  existing + a new traveller and two legs, the new traveller shows on the client's members, accountant
  generates, PDF text has the passenger rows, legs, CGST/SGST rows and the new number. Accounting invariants
  stay green.

## Starting number

Fresh from 00000001 per series - the user's call, made after the ePrompt-collision warning (Rule 46: the
agency must not reuse a number ePrompt already issued this FY under the same GSTIN). A "last number used"
seed per series is the follow-up if they need it.
