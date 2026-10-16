# Booking cancellation and refunds - design

Date: 2026-10-16. Status: design approved in chat ("do the changes"). Builds on the booking log
flow (`2026-10-16-booking-log-flow-design.md`).

## Goal

An agent cancels a booking in one step and the system works out, records and tracks the money:
what the customer gets back and what the supplier owes the agency. Accounts settles both sides
from one worklist. Nobody has to know what a credit note is.

## Decisions (user, 2026-10-16)

| Question | Answer |
|---|---|
| Who marks money as moved | Agent/Owner cancels; only Owner/Accountant marks customer refund paid or supplier refund received |
| What can be cancelled | Whole booking only |
| Refund mode | Always paid back (no keep-as-credit) |
| Agency fee | Typed per cancellation, default 0 |

## The flow

1. **Cancel booking** (Agent or Owner; from the Bookings page row or the lead's booking card).
   Inputs: reason*, supplier cancellation charge (Rs), agency cancellation fee (Rs). Live preview:
   - Customer paid = receipts recorded against this booking (net of refunds already made).
   - Customer refund due = max(0, customer paid - supplier charge - agency fee).
   - Expected back from supplier = max(0, paid to supplier - supplier charge); when the booking has
     no supplier bill, max(0, booking net cost - supplier charge).
2. **Accounts > Refunds** (Owner, Accountant). Two lists:
   - *To pay customers*: Mark paid (amount, date, mode, reference). Partial allowed.
   - *Expected from suppliers*: Mark received (amount, date, mode, reference). Partial allowed.
3. The booking shows Cancelled plus "Refund due Rs X / Refunded" and "Rs Y expected from supplier /
   Received". Agents see the status; they cannot move money.

## Accounting (automatic, inside the cancel transaction)

- **Live tax invoice on the booking**: create and issue a credit note (reason BOOKING_CANCELLED),
  `cancellationFee = min(supplier charge + agency fee, invoice taxable value)`. The kept amount
  stays invoiced revenue. The customer refund due is the credit note's refundable amount (capped
  by cash actually received). Any part of the invoice still held as Unearned Tour Revenue (2120)
  after the credit note is recognised to Sales now (fixes QA finding F-19: a fee kept on a
  cancelled trip was never recognised because the trip never departs).
- **No tax invoice** (money held as proforma advance): nothing posted at cancel. Mark paid records
  a REFUND receipt against the advance (Dr Client Advances Held 2110, Cr Bank/Cash). If the agency
  keeps part of an advance, the Refunds list shows "Kept Rs X - raise a cancellation-charge
  invoice"; the accountant raises it with the existing invoice screen (GST needs a tax invoice).
- **Supplier bill in Draft**: cancelled (nothing was posted for it).
- **Supplier bill approved/paid**: a supplier credit note reduces the bill to the supplier charge
  (GST split pro rata to the bill). Its refundable part (cash already paid above the charge) is
  what Mark received settles, through the existing supplier refund path.
- **No supplier bill**: the expected amount and receipts are tracked on the booking only.

## Data

Tenant migration V50, new columns on `booking`:
`cancellation_supplier_charge`, `cancellation_agency_fee`, `customer_refund_due`,
`supplier_refund_expected`, `supplier_refund_received` (numeric(14,2), default 0).
Customer refunded = existing `refunded_total_inr` (synced from REFUND receipts). Customer side
`refund_state` is derived: NOT_APPLICABLE when nothing is due, else REFUND_PENDING /
PARTIALLY_REFUNDED / REFUNDED.

## API

- `POST /api/bookings/{id}/cancel` (Owner, Agent) - `{reason, supplierCharge, agencyFee}`.
- `GET /api/bookings/{id}/cancel-preview` (Owner, Agent) - the three preview numbers.
- `GET /api/bookings/refunds` (Owner, Accountant) - cancelled bookings with anything outstanding.
- `POST /api/bookings/{id}/refunds/customer` (Owner, Accountant) - `{amount, paidOn, paymentMode, reference}`.
- `POST /api/bookings/{id}/refunds/supplier` (Owner, Accountant) - same shape.

Removed: `PATCH /api/bookings/{id}/refund` and the refund half of "Deadlines & Refund".
`PATCH /api/bookings/{id}/status` no longer accepts CANCELLED (use `/cancel`).

## Rules

- Only PENDING or CONFIRMED bookings can be cancelled.
- A payment can never exceed what is still outstanding on that side.
- Agents get 403 on the two money endpoints and the refunds list.

## Not in scope

Partial (per-traveller) cancellation, keep-as-credit, a default agency fee in Settings, undoing a
cancellation, chasing a customer who paid less than the kept amount.

## Testing

Unit: preview maths (zero floors, no bill, no invoice). Service: invoice path issues a credit note
and recognises retained revenue; no-invoice path refunds the advance; draft bill cancelled; approved
bill gets a supplier credit note; overpayment rejected on both sides; agent forbidden on money
endpoints (controller slice). Chrome walkthrough on the local QA database.
