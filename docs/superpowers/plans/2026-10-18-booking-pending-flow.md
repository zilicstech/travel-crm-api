# Booking Pending-First Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every new booking (ad-hoc or from flight search) starts as **Pending** with only basic, service-specific details, and gets filled in later on Manage booking.
- The supplier payment step leaves the Manage page.
- The agent records supplier refunds and the accountant cross-checks the ones that went to the bank.

**Architecture:** This reuses the existing `POST /api/bookings` (`createBooking`, which already saves PENDING) and `PUT /api/bookings/{id}` (`updateBooking`), plus the lifecycle service built on this branch. New schema:
- 3 flight-time columns;
- 5 supplier-refund-claim columns.

Nothing new posts to the ledger directly. It all goes through the existing `BookingCancellationService.receiveFromSupplier`.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Flyway (tenant); Next.js 15 / React 19.

**Branch:** `feat/bookings-and-refunds` in both repos, adding to the existing PRs.

**Spec:** the user's 6 points (2026-10-18) plus these answers:
- **Bank refund:** the agent's mark is a *claim*. It posts to Bank only when the accountant confirms it. Wallet refunds post at once.
- **Supplier pay:** done from Accounts → Supplier Bills. The bill is auto-drafted on confirm. Remove the one-step endpoint and modal.
- **Confirm gate:** a reference and a selling price greater than 0 are required.

## Global Constraints
- Local DB only, `voyra_crm_dev`. Never the prod `.env` DB, never the Voyra or globalexplorer tenants.
- New migration: `V53__booking_pending_flow.sql`. Never edit V1–V52.
- Every new DTO field gets an `@Schema`, for the blueprint audit §8.13.
- No direct ledger posting from the lifecycle or booking services.
- The UI label for `PENDING` is **"Pending"**. Remove "Draft" from booking-status display everywhere.
- Never push to main. Run the regression-tester (pre-merge) before handover.

## Review Focus
1. **Owner deletes a booking that has moved past Pending through a stale page.** The server must refuse with 409, not just hide the button. The test lives in Task 1.
2. **An agent claims more bank refund than is still expected, or claims again while a claim is open.** Expect 400. The accountant's confirm can never exceed the claim. Tests in Task 3.
3. **An agent calls the refund endpoints on another agent's booking.** Expect 404 through the access check. Test in Task 3.
4. **Confirming a Pending booking with no PNR or confirmation number, or with selling price 0.** Expect 400 with the message "Add the booking reference and selling price before confirming". Test in Task 1.
5. **Quick book from flight search with 0 travellers, or more infants than adults.** The modal blocks it. A passenger name is required by `@NotBlank`. Covered by the e2e test in Task 6.

---

### Task 1: Backend – Pending creation, confirm gate, delete rule, flight times, vendor on update

**Files:**
- Create: `travel-crm-backend/src/main/resources/db/migration/tenant/V53__booking_pending_flow.sql`
- Modify:
  - `entity/Booking.java`
  - `dto/BookingCreateRequest.java`, `dto/BookingUpdateRequest.java`, `dto/BookingResponse.java`
  - `service/BookingService.java`: `createBooking` ~:134, `updateBooking` ~:432, `updateStatus` ~:603, `deleteBooking` ~:684, mapper
- Test: `BookingServiceTest` (or the existing booking service test class)

**Interfaces – Produces:**
- New booking fields: `flightDepartTime` and `flightArriveTime` (String "HH:mm", VARCHAR(5), the same convention as `transfer_time`), and `flightArriveDate` (LocalDate).
- These are on Create, Update and Response.
- `BookingUpdateRequest.vendorId` (String) and `BookingUpdateRequest.destination` (String).

**Steps:**
- [ ] **Migration.** Add `flight_depart_time VARCHAR(5)`, `flight_arrive_time VARCHAR(5)`, `flight_arrive_date DATE`, plus the Task 3 claim columns (see Task 3), so there is one migration.
- [ ] **Failing tests:**
  - `createWithoutFareSavesPendingAtZero`: create with no netCost or sellingPrice gives PENDING with both at 0, and no supplier bill.
  - `confirmRequiresReferenceAndPrice`:
    - PENDING with no `pnr`/`hotelConfirmationNo`/`visaApplicationNo`/`transferVoucherNo` (whichever matches the type; PACKAGE uses `pnr`) gives 400 "Add the booking reference and selling price before confirming";
    - sellingPrice 0 gives the same 400;
    - with both present it becomes CONFIRMED.
  - `confirmDraftsSupplierBillOnce`: confirming with `vendorId` and netCost > 0 calls `supplierInvoiceService.createAutoDraft` once. It does not call it again if a live bill exists (`supplierInvoiceRepository.findByBookingIdAndStatusNot(..., CANCELLED)` is non-empty).
  - `deleteOnlyWhilePending`: CONFIRMED gives 409 "Only a pending booking can be deleted". PENDING with no accounting records is deleted.
  - `updateSetsVendorAndDestination`: update applies both fields.
- [ ] **Implement:**
  - Drop `@NotNull` on Create `netCost`/`sellingPrice` and default nulls to `BigDecimal.ZERO` in `createBooking`.
  - Keep the existing create-time auto-draft (it already needs netCost > 0).
  - Add the gate and the auto-draft in `updateStatus` for the PENDING→CONFIRMED transition only.
  - Add the status check at the top of `deleteBooking`.
  - Map the new fields.
  - `logBooking` (lead path, saves CONFIRMED) is untouched.
- [ ] **Verify:** `rtk ./mvnw test -Dtest=BookingService*` passes.
- [ ] **Commit:** `feat(bookings): pending-first create, confirm gate, delete only while pending, flight times`

### Task 2: Backend – remove the supplier-payment step from the lifecycle

**Files:**
- Modify:
  - `service/BookingLifecycleService.java`: remove the SUPPLIER step, the `PAY_SUPPLIER` action and the `paySupplier` method; change the "Draft" summary text at :330 to "Pending - waiting for supplier confirmation"
  - `controller/BookingLifecycleController.java`: remove `POST /{id}/supplier-payments`
  - `util/BookingNextStep.java`: remove the "Pay supplier ₹X" branch and the `supplierPaidInr` parameter if it is then unused
  - `BookingService` caller (~:850)
- Delete: `dto/BookingSupplierPaymentRequest.java`, plus the matching tests in `BookingLifecycleServiceTest`, `BookingLifecycleControllerAuthTest` and `BookingNextStepTest`.

**Steps:**
- [ ] **Update tests:**
  - lifecycle steps for a live booking are `CREATED, VOUCHER, INVOICE, PAYMENT` only;
  - `allowedActions` never contains `PAY_SUPPLIER`;
  - the next step for a paid, invoiced booking is `null`.
- [ ] **Implement.**
- [ ] **Keep:** the `supplierBills` and `supplierPayments` records in the response (the Money record card still lists them).
- [ ] **Verify:** `rtk ./mvnw test -Dtest=BookingLifecycle*,BookingNextStep*` passes.
- [ ] **Commit:** `refactor(bookings): supplier payment leaves the booking lifecycle (Accounts > Supplier Bills)`

### Task 3: Backend – agent records the supplier refund; accountant confirms bank refunds

**Files:**
- Modify:
  - `entity/Booking.java`: claim columns from V53: `supplier_refund_claim_amount NUMERIC(14,2)`, `supplier_refund_claim_on DATE`, `supplier_refund_claim_reference VARCHAR(100)`, `supplier_refund_claim_by VARCHAR(150)`, `supplier_refund_claim_at TIMESTAMP`
  - `service/BookingCancellationService.java`
  - `controller/BookingController.java`: refund endpoints
  - `service/BookingLifecycleService.java`: SUPPLIER_REFUND step and actions
  - `dto/BookingLifecycleResponse.java`: refunds gain `supplierRefundClaim { amount, on, reference, by }`
  - `util/BookingNextStep.java`
- Create: `dto/SupplierRefundRejectRequest.java` (`@NotBlank reason`)
- Test: `BookingCancellationServiceTest`, `BookingLifecycleServiceTest`, `BookingLifecycleControllerAuthTest` (or the BookingController auth test)

**Interfaces – Produces (endpoints):**
- `POST /api/bookings/{id}/refunds/supplier` (OWNER, AGENT; was OWNER, ACCOUNTANT), body `RefundSettlementRequest`:
  - `toAccount=true` (kept in the supplier wallet) runs the existing `receiveFromSupplier` posting now (Dr 1300 / Cr 2200 plus `creditWallet`).
  - `toAccount=false` (into the agency bank) only saves the claim. It posts nothing.
- `POST /api/bookings/{id}/refunds/supplier/confirm` (OWNER, ACCOUNTANT), body `RefundSettlementRequest` with amount ≤ claim, and paidOn, paymentMode and reference that default from the claim. It calls the existing receiveFromSupplier internals with `toAccount=false`, which post Dr Bank / Cr 2200, then clears the claim.
- `POST /api/bookings/{id}/refunds/supplier/reject` (OWNER, ACCOUNTANT), body `{reason}`. It clears the claim, adds an audit/timeline note, and the agent re-marks.
- Lifecycle action strings: `SUPPLIER_REFUND` (now ops = owner + agent, only while there is no open claim), `CONFIRM_SUPPLIER_REFUND` and `REJECT_SUPPLIER_REFUND` (accounts, only while a claim is open). `CROSS_CHECK` is unchanged but also requires no open claim.

**Steps:**
- [ ] **Failing tests:**
  - `agentWalletRefundPostsImmediately`
  - `agentBankRefundStoresClaimWithoutPosting`: no `supplierCreditNoteService.receiveRefund` call, and the claim fields are set.
  - `claimOverExpectedRejected`: amount > expected − received gives 400.
  - `secondClaimWhileOpenRejected`: 400 "A bank refund is already waiting for the accountant's check".
  - `confirmPostsAndClears`
  - `confirmAboveClaimRejected`
  - `rejectClearsClaim`
  - `crossCheckBlockedWhileClaimOpen`
  - Auth: an agent on another agent's booking gets 404 on `/refunds/supplier`; an agent gets 403 on `/confirm` and `/reject`; an accountant gets 403 on `/refunds/supplier`.
  - Lifecycle: the agent sees supplier-refund amounts (expected, received, claim) but still no net cost or bills; while a claim is open the step summary is "₹X claimed into bank – waiting for accountant's check".
- [ ] **Implement:**
  - Agent access: replace `lock(bookingId)` on the agent path with lock-then-`findAccessibleBooking`, the same as `BookingLifecycleService.lockAccessible`.
  - Extract the posting loop of `receiveFromSupplier` into a private method used by both the wallet path and confirm.
  - `settled()` still treats only *received* as received.
  - The next step reads: agent "Record supplier refund"; claim open "Check supplier refund in bank".
- [ ] **Verify:** `rtk ./mvnw verify` (the full suite plus the blueprint audit) passes.
- [ ] **Commit:** `feat(refunds): agent records supplier refund, accountant confirms bank receipts`

### Task 4: Frontend – Pending label, delete rule, supplier step removal, refund claim UI

**Files:**
- Modify:
  - `lib/api/enums.ts:234` (`PENDING: 'Pending'`)
  - `components/ui/Badge.tsx:33`, `components/lead/ServiceBookings.tsx:27`, `lib/mockData.ts:170`, `components/bookings/BookingsList.tsx:122`
  - `components/bookings/lifecycle/LifecycleTracker.tsx`: drop SUPPLIER from titles and buttons; add the `CONFIRM_SUPPLIER_REFUND` 'Confirm in bank' and `REJECT_SUPPLIER_REFUND` 'Not received' buttons
  - `components/bookings/lifecycle/LifecycleModals.tsx`: delete `PaySupplierModal`; add `ConfirmSupplierRefundModal`, which is prefilled from the claim and has mode, reference, date and amount (≤ claim)
  - `components/bookings/BookingManage.tsx`:
    - Delete shows only when `status === 'PENDING'`;
    - remove the PAY_SUPPLIER wiring;
    - wire confirm and reject (reject uses the existing `confirmAction` plus a reason prompt modal);
    - drop the "Supplier paid" metric
  - `components/accounts/RefundSettleModal.tsx`: supplier side labels "Into agency bank (accountant will verify)" / "Kept in supplier wallet"
  - `lib/api/services/bookingLifecycle.ts`: remove `paySupplier`; add `confirmSupplierRefund` and `rejectSupplierRefund`
  - `lib/api/types.ts`: the claim type
  - `components/shared/CancelBookingModal.tsx`: any "Draft" copy

**Steps:**
- [ ] **Implement.**
- [ ] **Verify:** `rtk npx tsc --noEmit` and `rtk npm run lint` are clean. `grep -rn "'Draft'" components lib` shows only invoice-status uses.
- [ ] **Commit:** `feat(bookings-ui): Pending label, delete while pending, supplier refund claim/confirm`

### Task 5: Frontend – ad-hoc New booking (Pending, service-specific) and Edit details on Manage

**Files:**
- Rewrite: `components/shared/BookingFormModal.tsx`. It calls `createBooking` (`lib/api/services/bookings.ts:53`, used through `lib/bookingStore.ts` so the list refreshes) instead of `logBooking`.
- Create:
  - `components/bookings/BookingServiceFields.tsx`: per-type inputs, shared by the New and Edit modals
  - `components/bookings/BookingTravellersField.tsx`: chips from `GET /api/clients/{id}/members` (`lib/api/services/clients.ts:54`) plus free-text add, each row `{name, paxType}`
  - `components/bookings/BookingDetailsModal.tsx`: Edit details, calling `updateBooking`
- Modify: `components/bookings/BookingManage.tsx`: an "Edit details" action (ops roles, while not cancelled)

**Interfaces – Produces:**
- `BookingServiceFields({ type, value, onChange, mode: 'create' | 'edit' })`, where `value` is a partial booking body using the flat backend keys (`flightFrom`, `hotelCheckIn`, ...).
- `BookingTravellersField({ clientId, value: {passengerName, paxType}[], onChange })`. Passengers are sent with `fareAmount: 0`.

**Fields by type (create; edit adds the extra items in brackets):**

| Type | Required at create | Optional at create | Added on edit |
|---|---|---|---|
| FLIGHT | From and To (`AirportPicker`), departure date | return date (gives tripType Round Trip), airline, flight no, depart/arrive time, cabin, travellers | PNR, ticket no |
| HOTEL | City (`CityPicker`), check-in, check-out | hotel name, rooms, room type, board basis | confirmation no |
| VISA | Country (`CountryPicker`), travel date | travellers | application no, applied/appointment/issued dates |
| TRANSFER | pickup, drop-off, date | time, vehicle type | voucher no |
| PACKAGE | destination, start date | end date | reference (pnr) |

Every type has: client (with Add client), agent (owner only), supplier (`VendorPicker`, optional), notes.

`destination` is derived for display and the NOT NULL column:
- FLIGHT: "DEL → BOM";
- HOTEL: "hotelName, city", or just city;
- VISA: "Visa – country";
- TRANSFER: "pickup → dropoff".

Fare (net and sell) is not on the New modal. It is added later through the existing "Edit price & cost" modal. The voucher comes later through `BookingDocuments`.

**Steps:**
- [ ] **Implement.** Reuse `fieldsFor` / `FlightSectorEditor` from `components/lead/ServiceDetailFields.tsx` only if it maps without adapters. Otherwise use plain inputs with the house pickers.
- [ ] **On create:** route to `${basePath}/bookings/{id}` (Manage) with a toast "Saved as pending – add PNR, fare and voucher when booked".
- [ ] **CREATED step:** when the booking is pending and confirm is blocked, show the hint "Add PNR and fare first" (driven by the backend 400 message on Mark confirmed).
- [ ] **Verify:** `rtk npx tsc --noEmit`, `rtk npm run lint`.
- [ ] **Commit:** `feat(bookings-ui): pending ad-hoc booking with service-specific fields; edit details on manage`

### Task 6: Frontend – Quick book from flight search

**Files:**
- Modify: `components/flights/FlightSearchPanel.tsx`. In standalone mode (no `proposal` prop) each fare row gets a "Quick book" button next to Copy (~:522).
- Create: `components/flights/QuickBookFlightModal.tsx`

**Behaviour:**
- The modal shows the flight read-only: airline, flight no, from → to, depart date and time, arrive date and time, cabin, stops, supplier.
- Inputs: client (`ClientPicker` + `AddClientModal`), agent (`AgentPicker`, owner only), and `BookingTravellersField`.
  - The traveller rows are prefilled to the search's adults/children/infants counts with `paxType` ADULT/CHILD/INFANT.
  - The names come from the client's members.
  - Save is blocked until every row has a name and infants ≤ adults.
- It calls `createBooking` with:
  - `type: 'FLIGHT'`, `destination: "${origin} → ${destination}"`
  - `airline`, `flightNumber`, `flightFrom`, `flightTo`, `flightCabin`, `journeyDate: departDate`
  - `flightDepartTime: hhmm(departTime)`, `flightArriveTime`, `flightArriveDate`
  - `tripType: 'One Way'`, `supplier: offer.supplier`
  - `passengers` (fareAmount 0, sectors `[{sectorFrom, sectorTo, flightNumber, travelDate, cabinClass}]`)
  - no fare.
- Then it routes to the Manage page.
- No accountant entry, since the flight search route is owner and agent only.
- [ ] **e2e:** `e2e/booking-lifecycle.spec.ts`:
  - update the 'Draft' expectation to 'Pending';
  - remove the Pay-supplier part of test 1 and the accountant supplier-refund part of test 5. Instead, the agent marks the supplier refund into the bank, the accountant confirms it, then cross-checks;
  - new test `agent quick-books a flight from search as pending` (search DEL→BOM on the mock supplier, Quick book, pick a client, see the Manage page with status Pending and the CREATED step);
  - new test `ad-hoc hotel booking needs only basic fields`;
  - new test `delete hidden once confirmed`.
- [ ] **Verify:** `rtk npx tsc --noEmit`, `rtk npm run lint`, `rtk npm run build`.
- [ ] **Commit:** `feat(flights): quick book a searched flight as a pending booking`

### Task 7: Verification and handover
- [ ] `rtk ./mvnw verify` (backend).
- [ ] Rebuild the jar and restart :8080 against `voyra_crm_dev` with the scratchpad `run/backend.env`. Rebuild the frontend with `npm run build` and restart :3000.
- [ ] `rtk npx playwright test e2e/booking-lifecycle.spec.ts e2e/accounting-billing-model.spec.ts`.
- [ ] Chrome pass with the QA logins:
  - **vikram.rao (agent):** quick book from search; ad-hoc hotel; edit details; confirm (gate message, then success); Delete disappears after confirm; cancel; record the supplier refund into the bank.
  - **neha.kapoor (accountant):** confirm or reject the claim; cross-check; pay the supplier from Supplier Bills.
  - **dana.owner:** the full flow.
- [ ] Run the regression-tester agent in pre-merge mode, fix what it finds, and re-run.
- [ ] Update `pr-descriptions/backend-PR.md` and `pr-descriptions/frontend-PR.md`. Copy this plan to `travel-crm-backend/docs/superpowers/plans/2026-10-18-booking-pending-flow.md`. Commit and push the branch, not main.

## Not in scope
- Storing the searched fare on a quick-booked flight. The fare is entered later, as the user asked.
- Round-trip quick book, since the search is one-way only.
- Changing the lead "log booking" path (`/api/bookings/log`).
- Re-syncing a draft supplier bill when net cost is edited after confirm. That is existing behaviour.
