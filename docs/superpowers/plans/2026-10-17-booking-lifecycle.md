# Booking Management: one lifecycle page per booking

Using: superpowers:writing-plans

## Context

The client's diagram defines booking management as one lifecycle that every role works on from the booking itself:

> Create (supplier, DRAFT) → Voucher upload → Invoice generate → Payment received → Payment done to supplier → Cancel → Refund sent to client → Refund received from supplier

Roles in the diagram:
- **Agent** works from Bookings and cancels.
- **Accountant** does booking management, raises invoices and refunds clients.
- **Owner** does everything.

Today the money steps are spread across Accounts worklists (Billing, Money In, Supplier Bills, Refunds). Each step there is a multi-field accounting form, and accountants cannot open a booking at all.

This plan rebuilds the Manage booking page around the diagram, using the decisions taken:
- **The booking page is the only place for actions.** Old Accounts worklists stay as reports, and their booking-linked actions link to the booking.
- **Payment before invoice is allowed.** It is held as an advance and applied automatically when the invoice is generated.
- **Supplier payment is one step.** The bill is created and approved behind the scenes.
- **PENDING is shown as "Draft".** This is a label change only, with no data change.

The ledger engine (journals, customer and supplier ledgers, GST, wallet) is not rewritten. The new booking endpoints orchestrate the existing services, so the accounting stays correct and the books stay consistent with what is already posted.

Branch: `feat/bookings-and-refunds` in both repos.

## The lifecycle model

The server computes eight steps per booking. Each step has a state: `DONE`, `PARTIAL`, `OPEN` (next to do), `WAITING` (an earlier step is needed), or `NA`.

| # | Step | Done when | Who acts |
|---|---|---|---|
| 1 | Booking created (Draft) | always; shows the supplier, and "Draft" until confirmed | Agent, Owner (Confirm) |
| 2 | Voucher uploaded | at least 1 booking document of a voucher/ticket kind (any document, if kinds are not typed) | Agent, Owner |
| 3 | Invoice generated | a live (not cancelled) invoice exists for the booking | Accountant, Owner |
| 4 | Payment received | invoice balance is 0 (PARTIAL when some was paid; an advance before the invoice counts) | Accountant, Owner |
| 5 | Supplier paid | the booking's supplier bills have balance 0 and total ≥ net cost (PARTIAL otherwise) | Accountant, Owner |
| 6 | Cancelled | status is CANCELLED (NA while live; an action, not a to-do) | Agent, Owner |
| 7 | Refund sent to client | after cancel: refunded ≥ refund due (NA if nothing is due) | Accountant, Owner |
| 8 | Refund received from supplier | after cancel: received ≥ expected (NA if nothing is expected); then the cross-check | Accountant, Owner (see assumption A1) |

Steps 6 to 8 appear only once a booking is cancelled. A live booking shows steps 1 to 5 plus the Cancel action.

## Backend (travel-crm-backend)

### New `BookingLifecycleService` and `BookingLifecycleController`
- The controller is `/api/bookings/{id}`. It is thin, with `@Valid` and `@PreAuthorize`.
- The service owns `@Transactional` and calls only existing services. It never writes the ledger directly.
- Every call first does a booking access check: agents only see their own bookings, the existing `findAccessibleBooking` rule.

1. **`GET /{id}/lifecycle`** (OWNER, AGENT, ACCOUNTANT) returns `BookingLifecycleResponse`:
   - `steps: [{ key, state, summary }]`, where key is `CREATED|VOUCHER|INVOICE|PAYMENT|SUPPLIER|CANCELLED|CLIENT_REFUND|SUPPLIER_REFUND`.
   - `invoices`: the existing `InvoiceListItemResponse` list for `bookingId`.
   - `receipts`: receipts with `bookingId`, plus receipts on its invoices.
   - `supplierBills` and `supplierPayments`.
   - `refunds`: due, paid, expected and received, and the checked flag.
   - `allowedActions: string[]`, computed from the role and the state. The frontend shows buttons only from this list, which is the single source of truth.
   - For AGENT, the supplier amounts (bills, payments, net cost) are omitted. Agents already cannot see net cost.
2. **`POST /{id}/invoice`** `{ serviceCategory?, dueDate?, notes? }` (OWNER, ACCOUNTANT):
   - Steps: `InvoiceDocumentService.createDraft` (the body is built from the booking, as today), then `issue`. Issue already auto-applies the client's wallet, so any advance is consumed.
   - Returns 409 if a live invoice exists or the booking is cancelled.
3. **`POST /{id}/payments`** `{ amount, paymentMode, reference?, receivedOn }` (OWNER, ACCOUNTANT):
   - With a live invoice that has a balance: `PaymentReceiptService.record` against it. Amount must be ≤ balance, else 400.
   - Without an invoice: record a deposit on account tagged with `bookingId`. Add an optional `bookingId` to the existing deposit path at `PaymentReceiptService:150`.
   - Amount must be ≤ selling price minus what is already received, else 400.
4. **`POST /{id}/supplier-payments`** `{ vendorId?, amount, paymentMode, reference?, paidOn, supplierInvoiceNumber?, overrideReason? }` (OWNER, ACCOUNTANT). In one transaction:
   1. Resolve the vendor: the request's `vendorId`, else `booking.vendorId`, else 400 "Pick the supplier". Save it to `booking.vendorId` when it was empty.
   2. Find a live bill for this booking and vendor. If there is none, use `SupplierInvoiceService.createAutoDraft` (single bill at net cost). Set `supplierInvoiceNumber` if given.
   3. If the bill is DRAFT, call `approve(id, overrideReason)`.
      - If the result is PENDING_APPROVAL (over the cost cap), roll back and return 409 "Over cost cap; needs owner approval".
      - The owner retries with `overrideReason`.
   4. Pay with `SupplierPaymentService` (the existing `paySupplier` path), where amount ≤ bill balance.
   - An optional bill file is uploaded afterwards through the existing `POST /supplier-invoices/{id}/file`.
5. **Refunds:** keep the existing `/{id}/refunds/customer|supplier|check`. There is no change except that they appear in `allowedActions`.

**Tests:**
- `BookingLifecycleServiceTest` covers step states for each scenario:
  - fresh booking;
  - voucher uploaded;
  - invoiced and partly paid;
  - an advance and then an invoice, where the balance is reduced by the advance;
  - supplier partly and fully paid;
  - cancelled with refunds due, partly done, and settled.
- Payment rules:
  - an amount over the balance is rejected;
  - a payment with no invoice is stored as a booking-tagged advance.
- Supplier one-step:
  - creates and approves the bill, then pays;
  - reuses an existing auto-draft;
  - over the cap with no override gives 409 and nothing is persisted;
  - a missing vendor gives 400.
- Controller slice: agent 403 on invoice, payments and supplier-payments. The agent's lifecycle JSON has no supplier amounts (assert the keys are absent, like the public proposal test).
- `mvn verify` passes, including the blueprint audit.

## Frontend (travel-crm)

### Manage page rebuilt as the lifecycle page (`components/bookings/BookingManage.tsx`)
- **Prop:** replace `isOwner` with `role: 'OWNER' | 'AGENT' | 'ACCOUNTANT'`. `basePath` gains `'/accounts'`.
- **Layout:**
  - Header: BK number, status, client, and the lead link (hidden for the accountant).
  - Metrics: Price, Received, Supplier paid (owner and accountant), Profit (owner).
  - **Lifecycle tracker**: the 8 steps as a vertical stepper. Each step card shows its state, its one-line summary, and its primary action button when that action is in `allowedActions`.
  - Under the tracker, a **Money record** card lists invoices with receipts, and supplier bills with payments. Invoice and bill numbers link to their full Accounts pages for the PDF.
  - Side panel keeps the secondary actions: Edit price, Deadlines, Cost breakdown, History, Feedback, Delete.
- **Data:** a new `lib/api/services/bookingLifecycle.ts` with `getLifecycle`, `generateInvoice`, `recordPayment` and `paySupplier`. After any action, reload the lifecycle and `refreshBooking(id)` from `lib/bookingStore.ts`.
- **New small modals in `components/bookings/lifecycle/`:**
  - `GenerateInvoiceModal`: category and due date, with a preview of the amount, any advance that will be applied, and GST.
  - `RecordPaymentModal`: amount (defaulting to the outstanding balance), mode, reference and date. It notes "held as advance until the invoice is generated" when there is no invoice.
  - `PaySupplierModal`:
    - fields: supplier (`VendorPicker`, defaulting to the booking's vendor), amount (defaulting to the outstanding net cost), mode, reference, date, supplier invoice number, optional file;
    - on a 409 over the cost cap, the owner sees an override reason field and the accountant sees "Ask the owner to approve".
  - Refunds reuse `SettleModal`, moved from `app/accounts/refunds/page.tsx` to `components/accounts/RefundSettleModal.tsx`.
- **Existing pieces reused as they are:**
  - `BookingDocuments` for the voucher step (read-only for the accountant);
  - `CancelBookingModal`;
  - `BookingPricingModal`, `BookingDeadlineRefundModal`, `BookingCostComponentsModal`, `RecordHistoryModal`.
- `CancellationPanel` is folded into steps 6 to 8.

### Draft label
- In `lib/api/enums.ts`, the booking-status display label for `PENDING` becomes `'Draft'`, along with its local type uses. Update the `BookingStatusBadge` colour.
- The list filter option and the "Mark confirmed" hint ("Supplier has confirmed") change to match.
- Grep the 4 files using `'Pending'` and touch only the booking-status ones. Payment and visa "Pending" stay.

### Accountant entry
- Add `app/accounts/bookings/page.tsx` (`BookingsList`, `basePath="/accounts"`, role ACCOUNTANT, no New Booking) and `app/accounts/bookings/[id]/page.tsx`.
- `app/accounts/layout.tsx`: put **Bookings** first in the nav after Dashboard.
- The list gains a **Next step** column: the first OPEN step from the lifecycle, e.g. "Invoice", "Collect ₹12,000" or "Pay supplier".
  - To keep the list one query, the backend list response gets `nextStep` computed from fields already on the booking: invoiced and received totals, supplier paid total, refund figures, and a document count.
  - Add `supplierPaidTotalInr` and `documentCount` to `BookingResponse` if they are missing.
  - A "Needs action" filter (`nextStep` not null) gives the accountant their worklist.

### Old Accounts screens become reports
- **Billing:** the "Raise invoice" button becomes a link to the booking.
- **Refunds:** the settle buttons become links to the booking. The tabs stay as the lists.
- **Supplier Bills:** for booking-linked rows, Pay links to the booking. Bills not tied to a booking (overheads) and supplier advances keep their actions there.
- **Money In / Invoices:** booking-linked rows link to the booking. Non-booking receipts, deposits and misc invoices keep their actions.
- No page is deleted. GST, aging, journals and reconciliation are untouched.

## Tasks (in order, each one committed)

1. Backend: lifecycle step computation and `GET /lifecycle` with role masking, plus tests.
2. Backend: `POST invoice` and `POST payments` (including the booking-tagged advance), plus tests.
3. Backend: `POST supplier-payments` one-step, plus tests. Then the list's `nextStep`, `supplierPaidTotalInr` and `documentCount`. Run `mvn verify`.
4. Frontend: the API service, role prop, Draft label, and the accountant routes and nav.
5. Frontend: lifecycle tracker, Money record card, the three new modals, and the moved `RefundSettleModal`.
6. Frontend: the list's Next step column and Needs action filter. Turn the old Accounts worklist actions into links.
7. e2e `e2e/booking-lifecycle.spec.ts`:
   - The owner takes a fresh booking through Confirm, voucher upload, Generate invoice, Record payment and Pay supplier, and every step shows DONE.
   - Advance first, then invoice: the invoice balance is reduced.
   - The accountant sees no Cancel or Delete, while the money actions are present.
   - The agent sees no money buttons.
   - Cancel, then refund the client and receive the supplier refund: steps 7 and 8 are DONE.
8. Verification and regression (below). Update the PR bodies.

## Assumptions to confirm on review

- **A1.** The diagram's Agent arrow reaches "Refund received from supplier". This plan lets the agent see that step's status but only the owner or accountant records money. Allowing agents to record it would mean opening `/refunds/supplier` to AGENT.
- **A2.** An advance is held in the client's wallet, which is shared across the client's bookings. If another booking for the same client is invoiced first, the advance could be applied there. The fix for that is to apply booking-tagged advances only to their own booking; it is not included unless you want it.
- **A3.** "Voucher uploaded" counts any document on the booking. There is no voucher document type today.

## Verification

- Backend: `rtk ./mvnw verify`.
- Frontend: `rtk npx tsc --noEmit`, `rtk npm run lint`, `rtk npm run build`.
- Local stack only: jar on :8080 against `voyra_crm_dev`, prod build on :3000. Never use the prod DB, Voyra or globalexplorer.
- Browser run with the QA creds:
  - dana.owner: the full lifecycle on a new booking.
  - neha.kapoor (accountant): Accounts → Bookings → Needs action, then invoice, payment, supplier payment and refunds.
  - vikram.rao (agent): create, voucher, cancel, with no money actions.
- Run `rtk npx playwright test e2e/booking-lifecycle.spec.ts e2e/accounting-bookings.spec.ts`.
- Run the `regression-tester` agent in pre-merge mode, which covers the accounting integrity checks: trial balance, customer and supplier ledgers tie to the journals after the one-step flows. Fix what it finds and update `scratchpad/pr-all-*.md`.
- Save this plan as `travel-crm-backend/docs/superpowers/plans/2026-10-17-booking-lifecycle.md`.

## Not in scope

- Quick Book from search.
- A real DRAFT status in the database.
- Rewriting the ledger, GST or journal engine.
- Deleting the old Accounts pages.
