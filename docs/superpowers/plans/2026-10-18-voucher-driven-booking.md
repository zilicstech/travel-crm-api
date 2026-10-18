# Voucher-Driven Booking Status and Money Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:**
- A booking's status moves by itself:
  - the first voucher that makes it ready confirms it;
  - a nightly job completes it after travel.
- Its cost and selling price are the running total of its vouchers:
  - each voucher adds its own cost, price and supplier;
  - each voucher with a supplier drafts its own supplier bill.
- The Mark confirmed, Mark completed and Edit cost & price buttons go away.

**Branch:** `feat/bookings-and-refunds` (both repos).

## Decisions (user, 2026-10-18)
1. **Status.**
   - **Confirmed:** a voucher upload confirms the booking once it has a reference and a selling price above 0.
   - **Completed:** a nightly job marks a Confirmed booking Completed after its last travel date: return date, else check-out, else transfer date, else journey or departure date.
2. **One supplier bill per voucher.** Each voucher can name its own supplier; it defaults to the booking's supplier.
3. **No selling price input anywhere: cost + markup, price computed.** Wherever a person used to type a selling price, they now type the cost and a markup (% or ₹), and the selling price is worked out from those. That covers the voucher form, Log Booking on a lead service and the (removed) Edit cost & price. Proposal lines and the Flight/Hotel search modals already work this way. The markup starts from the agency's default for the service type (`listMarkupDefaults`), and the server computes the price; it never trusts a price sent from the browser.
4. **Vouchers after invoicing are allowed.**
   - The booking shows the amount not yet invoiced.
   - Accounts gets a change alert.
   - The accountant can generate an extra invoice for the difference.
   - A voucher can't be deleted once its bill is approved, or once removing it would take the price below what is already invoiced.

## Backend

### Schema
Migration V54 on `booking_document`:
- `net_cost NUMERIC(14,2) DEFAULT 0`
- `selling_price NUMERIC(14,2) DEFAULT 0`
- `vendor_id VARCHAR`
- `supplier_invoice_id VARCHAR`

### Markup maths
- A shared `util/MarkupMath.sellingPrice(cost, mode, value)` mirrors the frontend's `withMarkup`:
  - PERCENT gives cost × (1 + value / 100);
  - FLAT gives cost + value.
- It is rounded half-up to 2 decimals, and covered by a unit test.

### `BookingDocumentCreateRequest`
New optional fields, defaulting to 0 or to the booking's vendor:
- `netCost` (`@DecimalMin 0`)
- `markupMode` (PERCENT | FLAT), `markupValue` (`@DecimalMin 0`)
- `vendorId`

The server computes the voucher's `selling_price` with `MarkupMath`.

### `BookingLogRequest` (Log Booking on a lead)
- `sellingPrice` is replaced by `markupMode` and `markupValue`, and the server computes the price.
- The fields are stored on the booking's existing `markup_mode` / `markup_value`.

### `BookingDocumentService.addDocument`
Lock the booking first (`findByIdForUpdate`), then:
1. Add the voucher's cost and price to the booking's `netCost` and `sellingPrice`, and recompute profit.
2. Fill the booking's own reference when it is blank: `pnr`, `hotelConfirmationNo`, `visaApplicationNo` or `transferVoucherNo` by type, taken from the voucher's `referenceNumber`.
3. If the voucher has a supplier and a cost above 0, draft its supplier bill.
   - Use a new public `SupplierInvoiceService.draftForVoucher(booking, vendorId, cost, reference)`, which reuses `createAutoDraftSingle`.
   - Store the bill on the document as `supplier_invoice_id`.
4. If the booking is PENDING and `BookingService.assertReadyToConfirm` passes, set it to CONFIRMED.
5. If the booking was already CONFIRMED and the amounts changed, raise the existing accounting change alerts (`raiseChangeAlerts`) and an audit row.

### `deleteDocument`
Reverse the voucher's amounts. It is refused when:
- the voucher's bill is APPROVED, PARTIALLY_PAID or PAID ("Its supplier bill is already approved");
- removing its price would take the selling price below what is already invoiced (pre-GST line total of the live tax invoices).

A DRAFT or PENDING bill is cancelled along with the voucher.

### Lifecycle (`BookingLifecycleService`)
- **Removed:** the `CONFIRM` and `COMPLETE` actions.
- **`CREATED` summary:**
  - "Pending - upload the voucher with its cost and price to confirm";
  - "Confirmed";
  - "Completed".
- **`INVOICE` step:**
  - DONE when the uninvoiced amount is 0, where uninvoiced = `sellingPrice` − pre-GST total of the live tax invoices' lines;
  - PARTIAL, reading "₹X added since the last invoice", when something is invoiced and more remains;
  - `GENERATE_INVOICE` is allowed whenever the uninvoiced amount is above 0.
- **`generateInvoice`:**
  - First invoice: unchanged.
  - Later invoices: one explicit line, "Additional services - <voucher refs>", for the uninvoiced amount.

### `BookingNextStep`
- PENDING reads "Upload voucher".
- A confirmed booking with an uninvoiced amount reads "Invoice ₹X more".

### Status endpoint
`PATCH /{id}/status` stays for API clients. The UI stops calling it.

### `BookingCompletionJob`
- Nightly, at `${app.bookings.completion.cron:0 0 2 * * *}`.
- Runs per tenant the same way as `RevenueRecognitionJob`.
- Marks a CONFIRMED booking COMPLETED when its end date is before today, with an audit row.

### Tests
- **Adding vouchers:**
  - adding sums the amounts;
  - the first priced voucher with a reference confirms the booking;
  - a voucher without a price leaves it Pending;
  - a voucher with a vendor drafts its bill, and one without a vendor drafts none.
- **Deleting vouchers:**
  - a DRAFT bill is cancelled and the amounts are subtracted;
  - an approved bill refuses the delete;
  - an invoiced amount refuses the delete.
- **After invoicing:** a voucher added after the invoice raises the alert and makes the INVOICE step PARTIAL; the extra invoice covers only the difference.
- **Completion job:** completes past trips only and leaves pending or cancelled bookings alone.

## Frontend
- **`BookingManage`:**
  - remove the Mark confirmed and Mark completed buttons (the actions are gone from the lifecycle) and the "Edit cost & price" action;
  - delete `BookingPricingModal.tsx`, which has no other user.
- **New `components/shared/CostMarkupFields.tsx`:**
  - fields: Net cost, Markup (value plus a %/₹ toggle), then a read-only Selling price with profit and margin %;
  - the markup is prefilled from the agency default for the service type;
  - it is lifted out of the inline markup UI in `ServiceProposalTable`'s line form, which then uses it too.
- **`BookingDocuments` voucher form** (Manage page and lead booking card):
  - adds `CostMarkupFields` and **Supplier** (`VendorPicker`, defaulting to the booking's);
  - the amounts default to 0, so a plain extra document needs nothing;
  - each document row shows its cost and price.
- **Log Booking modal** (`components/lead/ServiceBookings.tsx`): the Selling price input is replaced by `CostMarkupFields`, prefilled from the proposal's markup when the service has one.
- **Manage page metrics:** supplier cost and price now come from the vouchers, and the INVOICE step shows "₹X added since the last invoice".
- **e2e:**
  - the pending booking is confirmed by uploading a priced voucher with a reference, not by a button;
  - delete stays hidden once confirmed;
  - a second voucher raises the totals and the extra invoice is for the difference;
  - deleting a voucher with an approved bill is refused.

## Verification
- `mvn verify`
- `tsc`
- `next build`
- `e2e/booking-lifecycle.spec.ts`
- A Chrome pass as agent and accountant
- A pre-merge `regression-tester` run, including the books tie-out

## Not in scope
- Bookings logged from a lead (`/log`) keep their single bill at the logged cost, and their existing voucher carries no amounts.
- Editing a voucher's amounts in place: to correct one, delete the voucher and add it again.
