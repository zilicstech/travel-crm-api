# Booking Cancellation and Refunds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One-step booking cancellation that computes, records and tracks the customer refund and the supplier refund, settled by Accounts from one worklist.

**Architecture:** A new `BookingCancellationService` orchestrates existing accounting services (`CreditNoteService`, `SupplierCreditNoteService`, `SupplierInvoiceService`, `PaymentReceiptService`) inside one transaction. A pure `CancellationMath` holds the preview arithmetic. Five new booking columns hold the cancellation figures. Frontend: one `CancelBookingModal`, one Accounts > Refunds page.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Postgres (Flyway tenant migration), Next.js 15 / React 19.

**Spec:** `docs/superpowers/specs/2026-10-16-booking-cancellation-refunds-design.md`

## Global Constraints

- Whole-booking cancellation only; only PENDING or CONFIRMED bookings.
- Money endpoints and the refunds list: Owner and Accountant only. Cancel and preview: Owner and Agent.
- Refund is always paid back; agency fee typed per cancellation, default 0.
- Blueprint: no object storage inside `@Transactional`; every DTO field has `@Schema`; IDs via `UniqueIdResolver`; never edit an applied migration (next tenant migration is V50).

## Review Focus

- Customer paid less than supplier charge + agency fee: refund due must be 0, never negative.
- Paying more than outstanding (either side), or a second payment after fully settled: rejected with 400.
- Booking with an approved supplier bill that is unpaid: credit note lowers the payable, expected supplier refund is 0.
- Cancel called twice or on a Completed booking: rejected.
- Agent calling a money endpoint: 403.

---

### Task 1: Cancellation maths (backend, pure)

**Files:** Create `util/CancellationMath.java`; Test `util/CancellationMathTest.java`.

**Produces:** `CancellationMath.customerRefundDue(BigDecimal paid, BigDecimal supplierCharge, BigDecimal agencyFee)` and `CancellationMath.supplierRefundExpected(BigDecimal paidToSupplier, BigDecimal supplierCharge)` - both `max(0, ...)`, nulls as 0, scale 2.

- [ ] Tests: 10000 paid, 2000 + 500 kept -> 7500; 1000 paid, 2000 kept -> 0; nulls -> 0; supplier 8000 paid, 2000 charge -> 6000; 0 paid -> 0.
- [ ] Implement, run, pass.

### Task 2: Columns, DTOs, cancel endpoint (backend)

**Files:** Create `db/migration/tenant/V50__booking_cancellation.sql`, `dto/BookingCancelRequest.java`, `dto/BookingCancelPreviewResponse.java`, `service/BookingCancellationService.java`; Modify `entity/Booking.java`, `dto/BookingResponse.java` + `BookingService.toResponse`, `controller/BookingController.java`, `service/BookingService.java` (updateStatus refuses CANCELLED; package-visible `markCancelled(booking, reason)` reusing the old cancel side effects), `service/RevenueRecognitionTenantService.java` (`recognizeRetainedOnCancellation(Invoice, BigDecimal creditedTaxable, LocalDate)`).

**Produces:** `BookingCancellationService.preview(String bookingId)`, `cancel(String bookingId, BookingCancelRequest)`; response fields `cancellationSupplierCharge, cancellationAgencyFee, customerRefundDue, customerRefunded (=refundedTotalInr), supplierRefundExpected, supplierRefundReceived`.

Cancel steps (one transaction): load accessible booking, assert status; sync payment; customer paid = received - refunded; mark cancelled; if a live tax invoice exists -> `CreditNoteService.create` + `issue` with `cancellationFee = min(kept, taxable)`, due = note refundable amount, then recognise the remaining deferred revenue; else due = `customerRefundDue(paid, charge, fee)`. Supplier bills: DRAFT/PENDING_APPROVAL -> `SupplierInvoiceService.cancel`; APPROVED/PARTIALLY_PAID/PAID -> `SupplierCreditNoteService.record` for `max(0, grandTotal - creditNoteTotal - remainingCharge)` with GST pro rata, expected += note refundable; no bills -> expected = `supplierRefundExpected(netCost, charge)`. Save columns, derive refund_state.

- [ ] Service tests (Mockito): invoice path issues credit note and stores its refundable amount; no-invoice path stores computed due; draft bill cancelled; approved bill gets supplier credit note; completed booking rejected; updateStatus(CANCELLED) rejected.
- [ ] Implement, run, pass.

### Task 3: Settle endpoints and refunds list (backend)

**Files:** Create `dto/RefundSettlementRequest.java` (`amount, paidOn, paymentMode, reference`); Modify `BookingCancellationService` (`payCustomer`, `receiveFromSupplier`, `listOutstanding`), `PaymentReceiptService` (`refundAdvance(Booking, RefundSettlementRequest)` - REFUND receipt, customer ledger REFUND_PAID debit, journal Dr 2110 Cr Bank/Cash), `BookingController` (3 endpoints, Owner/Accountant). Remove `PATCH /{id}/refund`, `BookingRefundUpdateRequest`, `BookingService.updateRefund`.

- [ ] Tests: pay over outstanding rejected; invoice path routes to `CreditNoteService.refund`; no-invoice path routes to `refundAdvance`; supplier receive splits across credit notes, else increments the booking column; agent 403 on money endpoints (controller slice).
- [ ] Implement, `mvn verify` green, commit.

### Task 4: Frontend cancel flow

**Files:** Create `components/shared/CancelBookingModal.tsx`; Modify `lib/api/types.ts`, `lib/api/services/bookings.ts`, `lib/bookingStore.ts`, `components/shared/BookingStatusActions.tsx` (cancel opens the modal), `components/lead/ServiceBookings.tsx` (Cancel booking in the expanded card), `components/shared/BookingDeadlineRefundModal.tsx` (deadlines only), both bookings pages (refund chips).

- [ ] Modal: reason*, supplier charge, agency fee; live preview from `cancel-preview` + local maths; confirm -> `POST /cancel`.
- [ ] `tsc` clean.

### Task 5: Accounts > Refunds page

**Files:** Create `app/accounts/refunds/page.tsx`; Modify accounts sidebar nav.

- [ ] Two tabs: To pay customers / Expected from suppliers; Mark paid / Mark received modal (amount defaults to outstanding, date today, mode, reference).
- [ ] `tsc` clean; Chrome walkthrough on local QA DB: cancel with and without invoice, pay customer partially then fully, receive supplier refund, agent cannot see money actions.
