# Client Credit Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each client has a credit limit. Used and available are computed from the ledger and from bookings. Agents are blocked from raising a booking price over the limit; the owner can override.

**Architecture:**
- One new column, `client.credit_limit`.
- One new read-only service, `ClientCreditService`, built on aggregate JPQL queries. It is called from:
  - client responses;
  - the voucher, log and booking price paths;
  - the accounts customer summary.
- On the frontend, a single `ClientCreditChip` component reused on every booking surface.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Flyway tenant migration; Next.js 15.

**Spec:** `docs/superpowers/specs/2026-10-18-client-credit-limit-design.md`

## Global Constraints
- **Migration:** tenant `V56__client_credit_limit.sql`: `ALTER TABLE client ADD COLUMN credit_limit NUMERIC(14,2)`. `NULL` means no limit.
- **Formula:**
  - Used = ledger balance INR (Σ debit_inr − credit_inr), plus Σ(sellingPrice − invoiced INR) over non-cancelled bookings, plus Σ(cancellationSupplierCharge + cancellationAgencyFee) over cancelled bookings with no issued tax invoice.
  - Available = limit − used.
- **Over-limit message (verbatim shape):** "This takes {client} ₹{over} over their credit limit (₹{available} available). Ask the owner to raise the limit." It is a 409 (`IllegalStateException`).
- **Override:** `overrideCreditLimit=true` is honoured only for `isAgencyOwner()`. An override is written to the log and recorded in the booking's audit diff.
- **Warning level:** amber at used ≥ 80% of the limit, red when available < 0.
- **Limit field:** every new DTO field needs `@Schema`. The limit is set or cleared after create by the owner only (403 / `AccessDeniedException` otherwise).
- **Branch:** `feat/soft-delete-and-accounts`. Local DB only.

## Review Focus
1. **Owner retries after a 409 with the override:** the voucher saves, and the same voucher is not double-counted. Test in Task 2.
2. **Editing a voucher to a lower price on an over-limit client:** never blocked. Test in Task 2.
3. **Client with `NULL` limit:** never blocked, and the chip shows "No limit". Tests in Tasks 1 and 2.
4. **Cancelled booking reopened through `PATCH /status`:** refused. Test in Task 2.
5. **Agent sends `creditLimit` on update:** 403, and the limit is unchanged. Test in Task 1.

---

### Task 1: Backend – limit column, credit computation, client API

**Files:**
- Create:
  - `V56__client_credit_limit.sql`
  - `service/ClientCreditService.java`
  - `dto/ClientCreditResponse.java`
  - `ClientCreditServiceTest`
- Modify:
  - `entity/Client.java` (`creditLimit`)
  - `ClientCreateRequest` (`creditLimit`)
  - `ClientUpdateRequest` (`creditLimit`, `noCreditLimit`)
  - `ClientResponse` and `ClientDetailResponse` (`creditLimit`, `creditUsed`, `creditAvailable`)
  - `ClientService` (create, update, mapping)
  - `ClientController` (`GET /{id}/credit`)
  - `ClientLedgerSummaryResponse` and `CustomerLedgerService.summaryForAllClients` (`creditLimit`, `creditAvailable`)
  - the repositories, for the aggregate queries

**Interfaces – Produces:**
- `ClientCreditResponse { BigDecimal limit; invoicedUnpaid; bookedNotInvoiced; cancellationCharges; advanceHeld; used; available }`
- `ClientCreditService.credit(Client) -> ClientCreditResponse`
- `ClientCreditService.creditFor(Collection<Client>) -> Map<String, ClientCreditResponse>`
- `ClientCreditService.assertWithinLimit(String clientId, BigDecimal increase, boolean override)`

**Steps:**
- [ ] **Tests:**
  - `usedSumsLedgerUninvoicedAndKeptCharges`
  - `advanceMakesUsedNegative`
  - `nullLimitNeverBlocks`
  - `agentOverLimitIsRefusedWithMessage`
  - `ownerOverrideAllowed`
  - `ownerWithoutOverrideStillRefused`
  - `agentCannotChangeLimit`
  - `agentMustSetLimitOnCreate`
  - `ownerMayCreateWithoutLimit`
- [ ] **Implement:** `assertWithinLimit` flushes the persistence context first, so earlier in-memory reductions (such as reversing a voucher) count.
- [ ] **Verify:** `./mvnw test -Dtest=ClientCreditServiceTest,ClientServiceTest` passes.

### Task 2: Backend – enforce on price paths

**Files:**
- Modify:
  - `BookingService`:
    - `applyVoucher(..., boolean overrideCreditLimit)`;
    - `logBooking`;
    - `createBooking` and `updateBooking` when `sellingPrice` rises;
    - `updateStatus` refuses moving out of CANCELLED.
  - `BookingDocumentService` (pass the flag)
  - `BookingDocumentCreateRequest` and `BookingLogRequest` (`overrideCreditLimit`)

**Steps:**
- [ ] **Tests:**
  - `voucherOverLimitRefusedForAgent`
  - `voucherEditToLowerPriceNotBlocked`
  - `cancelledBookingCannotBeReopened`
- [ ] **Verify:** `./mvnw verify` passes and the audit reports COMPLIANT.

### Task 3: Frontend
- **Data layer:** `lib/api/types.ts` gets the credit fields; `lib/api/services/clients.ts` gets `getClientCredit(id)`.
- **New `components/shared/ClientCreditChip.tsx`:** `({ clientId, adding?: number })` renders:
  - "Available ₹X of ₹Y", or "No limit";
  - amber/red states;
  - with `adding`, a preview line "After this: ₹Z left" that turns red when it goes over.
- **Credit limit field:**
  - "Credit limit (₹)" is required for agents and optional for the owner (where blank means no limit);
  - it goes in AddClientModal and in AddLeadModal's inline client form;
  - the client edit screen lets the owner change the limit, or tick "No limit".
- **Where the chip goes:**
  - BookingFormModal, QuickBookFlightModal and the ServiceBookings Log modal;
  - the BookingDocuments voucher form, with `adding` = the selling-price preview;
  - the client page header, plus a Credit card;
  - the client lists, as an "Available" column;
  - Accounts › Customers, as Limit and Available columns, with over-limit clients first and in red.
- **Over-limit error:**
  - for the owner, a 409 whose message mentions "credit limit" shows `confirmAction` "Book over limit", which retries with `overrideCreditLimit: true`;
  - for an agent, it shows the error.
- **Verify:** `tsc` and `next build` pass.

### Task 4: Verify and hand over
- Live check on the local DB as agent and as owner.
- e2e:
  - a ₹50k limit;
  - a ₹40k voucher (amber);
  - a ₹20k voucher as the agent (blocked);
  - the owner override.
- Then the regression-tester run, then commit and push.
