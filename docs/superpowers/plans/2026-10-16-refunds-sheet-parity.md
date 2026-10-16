# Refunds: Sheet Parity (credit to account, cancellation type, cross-check) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three gaps between the CRM's cancellation flow and the client's old Cancellation Sheet: refunds settled to the client's or supplier's account balance instead of cash, the cancellation type (Void / Cancel / No show / Full refund), and the accountant's "cross checked by" stamp.

**Architecture:** Extends `BookingCancellationService` (branch `feat/booking-cancellations`). "To account" reuses the existing wallets: a customer deposit with no invoice (`PaymentReceiptService` wallet pool, auto-drawn on the next invoice) and a supplier advance (`SupplierPaymentService` advance pool). The refund document is still written (so the booking reads Refunded and the credit note closes), but its journal uses the advances account instead of Bank, and a matching wallet row is added without a second journal. One new tenant migration, V51.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Postgres (Flyway tenant migration), Next.js 15 / React 19.

**Spec:** gap list in the 2026-10-16 conversation (items 2, 5 and 6 of the sheet comparison); source sheets `Cancellation Sheet.xlsx`, `September Purchase Sheet.xlsx`.

## Global Constraints

- Agent picks the type when cancelling; reason becomes optional (the sheet's "ANOTHER REASON" column was never filled).
- Types: `VOID`, `CANCELLATION`, `NO_SHOW`, `FULL_REFUND`. `VOID` is offered for flights only.
- Settling to account, customer side: Dr Accounts Receivable (client) / Cr 2110 Client Advances Held (invoice path); no journal on the advance path (money is already in 2110). Customer ledger gets REFUND_PAID and a matching deposit credit, net zero.
- Settling to account, supplier side: Dr 1300 Supplier Advances (vendor) / Cr Accounts Payable (credit-note path). With no bill there is nothing posted to reverse, so it only updates the booking column, as cash does today.
- Wallet rows carry no `bookingId` (otherwise `BookingAccountingSync` would count them as money received on the booking).
- Cross-check: Owner/Accountant only; allowed only when nothing is outstanding on either side; stores the checker's display name and time. Cancellations made before V51 are back-filled as checked.
- Blueprint: every DTO field has `@Schema`; never edit an applied migration.

## Review Focus

- To-account settlement must not move Bank: no journal line on 1200/BANK for it.
- Wallet deposit must be drawable: `remainingWallet(clientId)` rises by the amount.
- Settle with neither `toAccount` nor `paymentMode`: 400, not 500.
- Cross-check while either side still has money outstanding: rejected.
- Booking response after to-account settle shows REFUNDED, and the refunds list drops it from "To pay".

---

### Task 1: Cancellation type (backend)

**Files:** Create `enums/CancellationType.java`, `db/migration/tenant/V51__refund_type_and_check.sql`; Modify `entity/Booking.java`, `dto/BookingCancelRequest.java`, `dto/BookingResponse.java` + `BookingService.toResponse`, `BookingCancellationService.cancel`.

- [ ] `BookingCancelRequest.type` `@NotNull`; `reason` optional. Stored reason = trimmed reason, else the type's label ("Void", "Cancellation", "No show", "Full refund").
- [ ] V51: `cancellation_type VARCHAR(20)`, `refund_checked_at TIMESTAMP`, `refund_checked_by VARCHAR(255)`; back-fill `refund_checked_at = COALESCE(cancelled_at, now())`, `refund_checked_by = 'Before refund tracking'` for rows already CANCELLED.
- [ ] Test: cancel stores type and falls back to the label when no reason.

### Task 2: Settle to account (backend)

**Files:** Modify `dto/RefundSettlementRequest.java` (`toAccount` Boolean; `paymentMode` no longer `@NotNull`), `CreditNoteService` (`refund(id, request, toWallet)` overload; journal credits 2110 when toWallet), `PaymentReceiptService` (`refundAdvance(..., toWallet)` skips the journal; new `creditWallet(clientId, amount, date, narration)`), `SupplierCreditNoteService` (`receiveRefund(id, request, toWallet)`; journal debits 1300 when toWallet), `SupplierPaymentService` (new `creditWallet(vendorId, amount, date, narration)`), `BookingCancellationService` (`payCustomer`, `receiveFromSupplier` route on `toAccount`; mode `ADJUSTMENT`).

- [ ] Tests (Mockito, `BookingCancellationServiceTest`): customer to account calls `refund(…, true)` then `creditWallet`; advance path calls `refundAdvance(…, true)` then `creditWallet`; supplier to account calls `receiveRefund(…, true)` then the supplier `creditWallet`; no mode and not to account rejected.
- [ ] Tests: `CreditNoteServiceTest` to-wallet refund posts a journal with 2110 and no bank line.
- [ ] Implement, run, pass.

### Task 3: Cross-check (backend)

**Files:** Modify `BookingCancellationService` (`markChecked(bookingId)`; `listOutstanding` also returns settled-but-unchecked), `BookingController` (`POST /{id}/refunds/check`, Owner/Accountant), `BookingResponse` (`cancellationType`, `refundCheckedAt`, `refundCheckedBy`).

- [ ] Tests: check rejected while outstanding; check stamps name and time; settled-unchecked appears in the list; agent gets 403 (controller slice).
- [ ] `mvn verify` green (tests + blueprint audit), commit.

### Task 4: Frontend

**Files:** Modify `lib/api/types.ts`, `lib/api/services/bookings.ts`, `components/shared/CancelBookingModal.tsx` (type chips, reason optional), `app/accounts/refunds/page.tsx` (Paid out / To account toggle in SettleModal, mode and reference hidden for to account; drop the fake "Supplier wallet" mode; third tab "To cross-check" with "Mark checked"; type badge on rows), `components/lead/ServiceBookings.tsx` (type in the cancelled line).

- [ ] `tsc` clean.
- [ ] Chrome on local QA DB: cancel as Void; settle customer to account and confirm the client's wallet grows; settle supplier to account; mark checked; the row leaves the list.
- [ ] Commit.
