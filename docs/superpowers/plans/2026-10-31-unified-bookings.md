# Unified Bookings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For agencies on a CRM plan, every Voyra flight or hotel booking is one row in the CRM Bookings list from the
moment it is sent to the supplier, carries the Voyra status, cancel, e-ticket and markup actions on its CRM detail,
and the separate Platform Bookings page leaves their navigation; every booking needs a client.

**Architecture:** The existing CRM mirror becomes a reconciler. The platform state machine queues the mirror on every
status move from BOOKING onwards; `PlatformBookingMirrorService.sync` runs ensure, ticket, fail and cancel phases in
order against the tenant schema (each REQUIRES_NEW, as the `SystemActor`, under a lock). `BookingService` gains four
mirror-only methods (create pending, confirm ticket, fail, markup-only reprice). The frontend extracts the platform
booking body into a card mounted on the CRM booking detail and points every CRM-plan link at the Bookings list.

**Tech Stack:** Spring Boot 3.3 / Java 17, JPA, Flyway (no new migration), JUnit 5 + Mockito, JaCoCo 100 %;
Next.js 15 / React 19, Vitest (100 % thresholds), Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-10-31-unified-bookings-design.md` (approved 2026-10-31; the
owner left both open questions to the defaults: Basic agencies keep the hidden CRM row, Set markup stays for both
products).

Repos: backend `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend`, frontend
`/Users/shivanshsrivastava/Projects/Voyra global/travel-crm`, both on branch `nitin-dev`. Work in the real repos, no
worktrees. Backend paths below are under `src/main/java/com/voyra/crm/` unless they start with `src/`.

## Global Constraints

- Never push; never touch `main`. Commit per task on `nitin-dev` with plain-prose messages ending in the attribution
  lines from the session's latest system reminder.
- Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`; never `source .env`. Run tests with
  `rtk ./mvnw test -Dtest=<pattern>`; each task ends with `scripts/coverage-gaps.sh '<pattern>' <class>` showing no
  missed line or branch in the classes it touched (check `target/site/jacoco/jacoco.xml` class counters when the
  script is silent). `rtk ./mvnw verify` must pass at the end of the backend tasks.
- Frontend: `rtk npx vitest run <files>` per task; `rtk npm run lint` must not exceed the baseline of 22 errors;
  `rtk tsc --noEmit` clean.
- Blueprint: controllers carry no logic; services own transactions; every cross-schema write is REQUIRES_NEW in a
  separate bean and restores `TenantContext` (use `SystemActor.call(tenantId, ...)`); `sync` and `setMarkup` never
  run inside a platform transaction.
- Logs carry no passenger names, passport numbers, emails or phones.
- Error copy (exact): "Client is required"; 409 `WAITING_FOR_VOYRA` "Voyra is still booking this; bill and collect
  once it is confirmed"; 409 `PRICE_BELOW_INVOICED` "This price is below what you have invoiced; adjust the invoice
  first"; 409 `MARKUP_NOT_APPLICABLE` "Markup can be set only on a confirmed booking"; fail reason "Voyra could not
  complete this booking; nothing was charged"; revive timeline "Voyra ticketed this booking after all"; card fallback
  "Booked with Voyra by another user of your agency. Ask them or the owner for its status."; next step "Waiting for
  Voyra".
- UI follows `travel-crm/UI_BLUEPRINT.md` and `components/ui` primitives.
- No new migration. The latest public migration is V36, the latest tenant migration V79.

## Rulings made while planning (deviations from the spec, smaller and equivalent)

1. **Agent visibility uses `BookingResponse.agentId`.** The mirror sets the CRM row's agent to the platform row's
   `bookedBy` (owner = tenant id), and a Voyra row's agent can never be edited, so `booking.agentId === session.userId`
   is exactly "this agent booked it". No `platformBookedBy` field is added. (Only a booker deleted since falls back to
   the owner, and a deleted agent cannot log in.)
2. **The client-money guard is one check.** Invoice drafts already refuse a booking that is not CONFIRMED or
   COMPLETED (`InvoiceDocumentService.createDrafts`), receipts and wallet credit attach to invoices, and the payment
   status override is already refused for a Voyra row (`findWritableBooking`). The only open path is
   `BookingLifecycleService.recordPayment` (an advance held before any invoice), plus `RECORD_PAYMENT` in the
   lifecycle's allowed actions. Task B5 closes both and pins the invoice refusal with a test.
3. **Repair attempt cap per status.** `queueMirror` records a `MIRROR_QUEUED` event; the repair job counts
   `MIRROR_FAILED` events created after the newest `MIRROR_QUEUED` event.

## Review Focus

1. **Two syncs at once on one row** (confirm thread and the poller after a fast ticket) must leave one CRM row and
   one supplier payment. Test in B3: `concurrentSyncsWriteOneBookingAndOnePayment`.
2. **Platform cancels while the CRM row is still PENDING** (mirror failed at BOOKING, then ticket and cancel landed
   before the repair job) must post the supplier payment and then the refund. Test in B3:
   `aCancelledRowWhoseMirrorNeverTicketedIsTicketedThenCancelled`.
3. **A second ticket download with a lower markup after invoicing** must refuse with no ticket row and no price
   change. Test in B4: `aDownloadBelowTheInvoicedPriceMakesNoTicket`.
4. **A non-INR agency** reprices at the voucher's stored rate, never today's. Test in B4:
   `markupRepricesAtTheVouchersStoredRate`.
5. **Old Platform Bookings links** for a row whose mirror has not run (no `tenantBookingId`) still open the platform
   detail instead of looping. Test in F3: `detailPageStaysWhenThereIsNoCrmBooking`.

---

### Task B1: Client required on every Voyra review

**Files:**
- Modify: `dto/FlightReviewRequest.java` (clientId, ~line 36), `dto/HotelReviewRequest.java` (clientId, ~line 44)
- Test: `src/test/java/com/voyra/crm/controller/PlatformBookingControllerTest.java`; update every test that reviews
  without a client (`PlatformBookingServiceTest`, `PlatformBookingServiceHotelTest` or equivalents, the starter
  no-client tests) to pass one.

**Interfaces:**
- Produces: `clientId` is `@NotBlank(message = "Client is required")`; schema text drops "Optional".

- [ ] **Step 1: Write the failing tests** `flightReviewWithoutAClientIs400` and `hotelReviewWithoutAClientIs400`:
  POST the review without `clientId`, expect 400 and the message "Client is required" in the error body.
- [ ] **Step 2: Run** `rtk ./mvnw test -Dtest=PlatformBookingControllerTest` and see both fail (200/201).
- [ ] **Step 3: Add the annotation** to both DTOs.
- [ ] **Step 4: Fix the tests that relied on a missing client.** `aStarterBookingWithNoClientHasNoMirror` becomes
  a sync test on a row with `clientId = null` built directly (old rows still exist), not a review.
- [ ] **Step 5: Run** `rtk ./mvnw test -Dtest='PlatformBooking*Test'`, all green.
- [ ] **Step 6: Commit** "Require a client on every Voyra flight and hotel review".

### Task B2: BookingService mirror-only methods (create pending, confirm ticket, fail, revive)

**Files:**
- Modify: `service/BookingService.java` (extract the body of `createBooking` at ~140-270 into a private
  `insertBooking(BookingCreateRequest request, boolean announce)`; `createBooking` calls it with `true`).
- Test: create `src/test/java/com/voyra/crm/service/BookingServiceVoyraTest.java`.

**Interfaces:**
- Produces (all package-visible or public, each asserting `SystemActor.isActive()` with
  `Assert.state(..., "Voyra bookings are written by the platform only")`, each `@Transactional` joining the caller's
  REQUIRES_NEW):
  - `public String createVoyraPending(BookingCreateRequest request, BookingDocument voucher)`: inserts with
    `announce = false` (no `BOOKING_LOGGED` timeline line, no `ServiceBookingStatusSync.onBookingLogged`, no service
    agent hand-over), skips the credit-limit check, status PENDING; saves `voucher` with `bookingId` set, no
    reference, `sellingPrice = MarkupMath.sellingPrice(netCost, FLAT, markupValue)`, vendor = `request.vendorId`, no
    supplier bill; booking net cost, selling price, profit, markup mode and value from that voucher; returns the
    booking id. Never confirms.
  - `public String confirmVoyraTicket(String bookingId, String reference, String ticketNo, java.util.function.Consumer<BookingDocument> describe)`:
    locks the booking (`findByIdForUpdate`); finds its E_TICKET or HOTEL_VOUCHER; when the booking is already
    CONFIRMED or COMPLETED with `reference` returns that voucher's `supplierInvoiceId` (idempotent); otherwise sets
    the booking reference (`setReference`) and `ticketNo`, the voucher's `referenceNumber`, runs `describe` on the
    voucher, drafts the supplier bill exactly as `applyVoucher` does (`supplierInvoiceService.draftForVoucher` with
    the purchase currency, price and rate), writes the `BOOKING_LOGGED` timeline line and `onBookingLogged` when the
    booking has a service (agent = booking agent), revives a fail-cancelled row (see `isFailCancelled`), sets
    CONFIRMED, audits, returns the drafted bill id.
  - `public void failVoyraBooking(String bookingId)`: locks; no-op when CANCELLED; sets CANCELLED, `cancelledAt`
    now, `cancelledBy = SystemActor.USER_ID`, `cancelReason` = the fail reason (Global Constraints),
    `refundState = NOT_APPLICABLE`, no cancellation money; a `BOOKING_REMOVED`-type timeline line on the lead when
    `leadId` is set (reuse the line `markCancelled` writes, minus `onBookingRemoved`); audits.
  - `static boolean isFailCancelled(Booking b)`: `CANCELLED && platformBookingId != null && SystemActor.USER_ID.equals(cancelledBy) && cancellationType == null`.
  - Revive (inside `confirmVoyraTicket`): clears `cancelReason`, `cancelledAt`, `cancelledBy`, sets `refundState`
    to its default for a live booking (`NOT_APPLICABLE`), writes the revive timeline line and an audit entry.

- [ ] **Step 1: Write the failing tests** in `BookingServiceVoyraTest` (Mockito, the style of `BookingServiceTest`):
  - `createVoyraPendingSavesAPendingBookingWithNoBillNoTimelineNoServiceSync`: assert status PENDING,
    `voucher.getSupplierInvoiceId()` null, `supplierInvoiceService` never called, `leadTimelineService` and
    `serviceBookingStatusSync` never called, selling price = net + markup.
  - `createVoyraPendingRefusesOutsideTheSystemActor`: IllegalStateException with the message above.
  - `confirmVoyraTicketDraftsTheBillMarksTheServiceAndConfirms`: bill drafted once with the voucher's purchase
    currency, price and rate; timeline line and `onBookingLogged(serviceId, agentId, agentName)`; status CONFIRMED;
    booking PNR = reference.
  - `confirmVoyraTicketTwiceDraftsOneBill`.
  - `failVoyraBookingCancelsWithNoMoneyAndLeavesTheServiceAlone`: CANCELLED, reason exact, cancelledBy "system",
    all cancellation money fields null, `onBookingRemoved` never called; second call is a no-op.
  - `confirmVoyraTicketRevivesAFailCancelledBooking`: cancel fields cleared, CONFIRMED, revive line written.
  - `aCancelledBookingWithCancellationMoneyIsNotRevivable`: `isFailCancelled` false when `cancellationType` set.
  - `createBookingStillAnnouncesAsBefore`: the existing path still writes the timeline line and service sync.
- [ ] **Step 2: Run** `rtk ./mvnw test -Dtest=BookingServiceVoyraTest`, see compile failure / red.
- [ ] **Step 3: Implement** the extraction and the four methods in `BookingService`.
- [ ] **Step 4: Run** `rtk ./mvnw test -Dtest='BookingService*Test'` green; coverage-gaps on `BookingService`.
- [ ] **Step 5: Commit** "Add the mirror-only booking writes: pending create, ticket confirm, fail and revive".

### Task B3: The mirror reconciler (tenant phases and sync)

**Files:**
- Modify: `service/booking/PlatformBookingMirrorTenantService.java`, `service/booking/PlatformBookingMirrorService.java`
- Modify: `repository/BookingRepository.java` (advisory lock query)
- Test: `src/test/java/com/voyra/crm/service/booking/PlatformBookingMirrorServiceTest.java`,
  `PlatformBookingMirrorTenantServiceTest.java`; a concurrency test under `src/test/.../booking/` that uses the
  Testcontainers base if one exists for booking ITs, else a Mockito test that interleaves two `sync` calls with a
  latch on the mocked repository.

**Interfaces:**
- Consumes: B2's `createVoyraPending`, `confirmVoyraTicket`, `failVoyraBooking`.
- Produces on `PlatformBookingMirrorTenantService` (each `@Transactional(propagation = REQUIRES_NEW)`):
  - `public String ensure(PlatformBooking pb, List<ItinerarySegment> segments, HotelStaySummary stay, List<PaxInput> passengers)`:
    takes `bookingRepository.lockPlatformBooking(pb.getId())` (native `select pg_advisory_xact_lock(hashtext(:id))`),
    then returns the existing CRM id if `findByPlatformBookingId` finds one; otherwise builds the same request as
    today's `create` / `createHotel` minus PNR, ticket number and issued date, and the voucher (purchase currency INR,
    purchase price = displayed, rate from `exchangeRateProvider` today, `charges(pb)`, net = displayed in base, FLAT
    markup = `defaultMarkupBase(pb, rate)`), calls `createVoyraPending`, sets nothing on the platform row (finish does).
    A hotel with no stay throws as today.
  - `static BigDecimal markupInr(PlatformBooking pb, BigDecimal ticketMarkup)`: `(gross == null ? 0 : gross - displayed) + ticketMarkup`, never below 0.
  - `public void ticket(String crmId, PlatformBooking pb, List<ItinerarySegment> segments, List<PaxInput> passengers)`:
    calls `confirmVoyraTicket(crmId, pnr, ticketNo(pb.getTicketNumbers()), describe)` where `describe` sets legs,
    travellers, details (flight) or the hotel details, and `issuedDate = today`; when the returned bill id is new
    (the voucher's bill is DRAFT), runs today's `payFromVoyraWallet` at the voucher's stored rate. Idempotent: an
    already-paid bill is not paid again (check bill status APPROVED/PAID before paying).
  - `public void fail(String crmId)`: `failVoyraBooking`.
  - `public void cancel(String bookingId, PlatformBooking pb)`: unchanged.
  - `create` and `createHotel` are removed (their mapping moves into `ensure` and `ticket`).
- `PlatformBookingMirrorService.sync(String id)`: guards as the spec (missing row, no client, DONE, status not in
  `{BOOKING, UNKNOWN, TICKETED, FAILED, CANCELLED}`). Then inside one `SystemActor.call`: `id = ensure(...)`; if
  `pb.getTicketedAt() != null` then `ticket(id, ...)`; if status FAILED then `fail(id)`; if status CANCELLED then
  `cancel(id, pb)`. `finish` unchanged. The ticket markup read for `ensure` is the newest
  `platform_booking_ticket.markup` for the row (`PlatformBookingTicketRepository.findTopByPlatformBookingIdOrderBySeqDesc`, add it), 0 when none.

- [ ] **Step 1: Write the failing tests:**
  - `syncAtBookingCreatesAPendingRowAndNothingElse` (ensure only; no ticket, fail or cancel call).
  - `syncAtTicketedEnsuresThenTicketsAndPaysOnce`.
  - `syncAtFailedEnsuresThenFails` and `syncAtFailedNeverPays`.
  - `aCancelledRowWhoseMirrorNeverTicketedIsTicketedThenCancelled` (Review Focus 2): call order ensure, ticket, cancel.
  - `ensureUsesThePublishedFareAsTheDefaultPrice`: FLAT markup = gross - displayed; selling = gross (INR agency).
  - `ensureWithNoPublishedFareUsesNoMarkup`.
  - `ensureCarriesTheNewestTicketMarkup`: newest ticket markup 500 makes selling = gross + 500.
  - `ensureReturnsTheExistingRowUnderTheLock`: lock query called before the lookup.
  - `concurrentSyncsWriteOneBookingAndOnePayment` (Review Focus 1).
  - `ticketTwicePaysTheBillOnce`.
  - Existing mirror tests (create, hotel, cancel, requireLinks) move to the new methods.
- [ ] **Step 2: Run** `rtk ./mvnw test -Dtest='PlatformBookingMirror*Test'`, red.
- [ ] **Step 3: Implement** the repository query, the tenant methods and the new `sync`.
- [ ] **Step 4: Run** the mirror tests and `PlatformBookingMirrorRepairJobTest` green; coverage-gaps on both services.
- [ ] **Step 5: Commit** "Make the CRM mirror a reconciler: ensure, ticket, fail and cancel phases".

### Task B4: State machine queues the mirror; repair cap per status

**Files:**
- Modify: `service/booking/PlatformBookingService.java` (`markBooking` ~812, `confirm` ~361-395, `applyLocked`
  FAILED branch ~838-846, `ticket` ~879-882, `cancelLocked` ~693-696, `resolve` ~215-218, `applySupplierResult`
  ~423-430, `scheduleMirror` / `scheduleMirrorCancel` ~907-914)
- Modify: `scheduler/PlatformBookingMirrorRepairJob.java`, `repository/PlatformBookingEventRepository.java`
- Test: `PlatformBookingServiceTest`, `PlatformBookingPollerTest` (if it asserts sync calls), `PlatformBookingMirrorRepairJobTest`

**Interfaces:**
- Produces: `private void queueMirror(PlatformBooking b)` on `PlatformBookingService`: when `b.getClientId() != null`
  sets `MirrorStatus.PENDING` and records event `MIRROR_QUEUED` `{status: b.getStatus().name()}`. Called in the same
  transaction as: the move to BOOKING (`markBooking`), TICKETED (`ticket`, replacing today's inline flag), FAILED
  (`applyLocked`), CANCELLED (`cancelLocked`, replacing the DONE-only flip).
- `sync` is called (outside any transaction) after: `confirm`'s book call returns or throws (before `awaitOutcome`,
  and in the `catch` before returning); `applySupplierResult` when the status is TICKETED or FAILED; `resolve` when
  TICKETED or FAILED; the three cancel paths as today. `scheduleMirrorCancel` merges into `scheduleMirror`.
- `PlatformBookingEventRepository`: `Optional<PlatformBookingEvent> findTopByPlatformBookingIdAndTypeOrderByCreatedAtDesc(String id, String type)`
  and `long countByPlatformBookingIdAndTypeAndCreatedAtAfter(String id, String type, Instant after)`.
- Repair job: attempts = count of `MIRROR_FAILED` after the newest `MIRROR_QUEUED` (all of them when none);
  `MAX_ATTEMPTS` stays 144.

- [ ] **Step 1: Write the failing tests:**
  - `markBookingQueuesTheMirror`, `aFailedAnswerQueuesTheMirrorAndSyncs`, `anAdminFailedResolveSyncs`,
    `aCancelQueuesTheMirrorWhateverItsStatus` (mirror FAILED before cancel ends PENDING).
  - `confirmSyncsAfterTheBookCallNotBefore`: Mockito `InOrder` provider `book` then `mirrorService.sync`.
  - `confirmSyncsEvenWhenTheBookCallHasNoAnswer`.
  - `aRowWithoutAClientIsNeverQueued`.
  - Repair job: `failuresBeforeTheLastQueueDoNotCountTowardsTheCap`.
- [ ] **Step 2: Run** `rtk ./mvnw test -Dtest='PlatformBookingServiceTest,PlatformBookingMirrorRepairJobTest'`, red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `rtk ./mvnw test -Dtest='PlatformBooking*Test'` green; coverage-gaps on the three classes.
- [ ] **Step 5: Commit** "Queue the CRM mirror on every platform status move from BOOKING on".

### Task B5: No client money while Voyra is booking; next step text

**Files:**
- Modify: `service/BookingLifecycleService.java` (`recordPayment` ~115, allowed actions ~300-310), `util/BookingNextStep.java`
- Test: `BookingLifecycleServiceTest`, `BookingNextStepTest`, `InvoiceDocumentServiceTest`

**Interfaces:**
- Produces: `static boolean waitingForVoyra(Booking b)` on `BookingService`: `platformBookingId != null && status == PENDING`.
  `recordPayment` throws `BookingConflictException("WAITING_FOR_VOYRA", <copy>, Map.of())` when true; the lifecycle
  never offers `RECORD_PAYMENT` for such a row; `BookingNextStep.of` returns "Waiting for Voyra" for it.

- [ ] **Step 1: Write the failing tests:** `recordingAPaymentWhileVoyraIsBookingIs409`,
  `noRecordPaymentActionWhileVoyraIsBooking`, `nextStepSaysWaitingForVoyra`, and
  `draftingAnInvoiceForAPendingVoyraBookingIsRefused` (pins today's refusal in `createDrafts`).
- [ ] **Step 2: Run** the three test classes, red (the last one may already pass; keep it).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** green; coverage-gaps.
- [ ] **Step 5: Commit** "Hold client payments on a Voyra booking until Voyra has ticketed it".

### Task B6: Markup moves the selling price (ticket download and Set markup)

**Files:**
- Modify: `service/BookingService.java`, `service/booking/PlatformBookingMirrorTenantService.java`,
  `service/booking/PlatformBookingTicketService.java` (`create` ~69-104), `controller/PlatformBookingController.java`
- Create: `dto/PlatformMarkupRequest.java` (`@NotNull BigDecimal markup`, same `@DecimalMin("0")` / `@Digits(fraction = 2)` messages as `TicketCreateRequest`)
- Test: `BookingServiceVoyraTest`, `PlatformBookingMirrorTenantServiceTest`, `PlatformBookingTicketServiceTest`,
  `PlatformBookingControllerTest`, `TenantStatusInterceptorTest` (PATCH refused while suspended)

**Interfaces:**
- Produces:
  - `BookingService.public void repriceVoyraMarkup(String bookingId, BigDecimal markupBase)` (SystemActor only):
    locks; status must be CONFIRMED or COMPLETED else 409 `MARKUP_NOT_APPLICABLE`; finds the Voyra voucher; new
    voucher selling = `MarkupMath.sellingPrice(net, FLAT, markupBase)`; new booking selling = sum over live vouchers;
    if new booking selling < `invoicedPrice(bookingId)` then 409 `PRICE_BELOW_INVOICED`; else sets voucher markup and
    selling, booking markup, selling, profit, audits, `syncPaymentIfInvoiced`. Never touches net cost, vendor, bill.
    Same price as now returns without writing.
  - `PlatformBookingMirrorTenantService.public void setMarkup(PlatformBooking pb, BigDecimal ticketMarkup)`
    (REQUIRES_NEW): reads the Voyra voucher's stored `purchaseRate`; `markupBase = toBase(INR, gross + ticketMarkup, rate) - net`
    (i.e. `toBase(INR, displayed + markupInr(pb, ticketMarkup), rate) - net`), calls `repriceVoyraMarkup`.
  - `PlatformBookingTicketService.create(bookingId, markup)`: no longer `@Transactional`. (1) `TransactionTemplate`:
    tenant-scoped read via `queryService.visible`, the TICKETED/FLIGHT/purged/markup checks as today, capture the
    row. (2) If `pb.getTenantBookingId() != null` and the tenant's `EntitlementService.resolve(tenantId)` has
    `CRM_CORE` and is not `readOnly`: `SystemActor.call(tenantId, () -> tenantService.setMarkup(pb, m))`; any
    exception propagates (no ticket). (3) `TransactionTemplate`: `findByIdAndTenantIdForUpdate`, compute `seq`,
    save the ticket, as today.
  - `PlatformBookingService.public PlatformBooking setMarkup(String bookingId, BigDecimal markup)` (outside a tx):
    `actsOn` check (owner, or the booking agent who handles the product); TICKETED only (409 `TICKET_NOT_AVAILABLE`
    copy reused with "Markup can be set only on a ticketed booking"); markup ≤ gross; 404 when `tenantBookingId` is
    null or the tenant lacks CRM_CORE; 402 `{code: READ_ONLY}` (`EntitlementService.assertWritable`) on a read-only
    plan; then the same `SystemActor.call(... setMarkup ...)`; returns the row.
  - `PATCH /api/platform-bookings/{id}/markup` body `PlatformMarkupRequest` → `PlatformBookingResponse`, roles OWNER
    and AGENT. `TenantStatusInterceptor` leaves it refused (423) while SUSPENDED (only the ticket POST is allowed).

- [ ] **Step 1: Write the failing tests:**
  - `repriceMovesSellingAndProfitButNotCostOrBill`, `repriceOnACancelledBookingIs409`,
    `repriceBelowTheInvoicedPriceIs409`, `repriceAboveTheInvoicedPriceIsAllowed`, `repriceToTheSamePriceWritesNothing`.
  - `markupRepricesAtTheVouchersStoredRate` (Review Focus 4: EUR agency, stored rate 0.011, provider never called).
  - `aDownloadSetsTheCrmMarkupBeforeSavingTheTicket` (InOrder), `aDownloadBelowTheInvoicedPriceMakesNoTicket`
    (Review Focus 3), `aDownloadOnBasicOrReadOnlyLeavesTheCrmAlone`, `aDownloadWithNoCrmRowJustSavesTheTicket`,
    `aSuspendedAgencyDownloadStillReprices`.
  - Controller: `patchMarkupReturnsTheBooking`, `patchMarkupValidatesLikeTheTicket` (three messages),
    `patchMarkupForbiddenToAccountants`; interceptor: `patchMarkupIsRefusedWhileSuspended`.
- [ ] **Step 2: Run** the five test classes, red.
- [ ] **Step 3: Implement.** Classify nothing new in `ControllerSkuTable` (same controller, NEVER_GATED).
- [ ] **Step 4: Run** `rtk ./mvnw test -Dtest='PlatformBooking*Test,BookingService*Test,TenantStatusInterceptorTest'`
  green; coverage-gaps; then `rtk ./mvnw verify` green.
- [ ] **Step 5: Commit** "Let the ticket markup set a Voyra booking's selling price, and add Set markup".

### Task F1: Book with Voyra needs a client and lands on the Bookings list

**Files:**
- Modify: `components/booking/BookWithVoyraModal.tsx` (picker ~365-370, `paxOk` gating ~172, `track` ~256-260),
  its test `components/booking/BookWithVoyraModal.test.tsx`

**Interfaces:**
- Consumes: `ClientPicker` (`required`, `onCreate(query)`), `AddClientModal` (`open, onClose, onClientCreated(client), initialName`), `useEntitlements().has('CRM_CORE')`.
- Produces: picker always rendered, label "Client", required, "Create New Client" opens `AddClientModal`; the created
  client is selected through `pickClient(client.id)` (so contact and first passenger seed); Next disabled until a
  client is picked; `track(id)` pushes `${base}/bookings?track=${id}` when CRM Core, else today's route.

- [ ] **Step 1: Write the failing tests:** `showsARequiredClientPickerOnBasicToo`, `nextIsDisabledWithoutAClient`,
  `aClientCreatedInlineIsSelectedAndSeedsTheContact`, `afterConfirmACrmAgencyGoesToBookings`,
  `afterConfirmABasicAgencyGoesToPlatformBookings`.
- [ ] **Step 2: Run** `rtk npx vitest run components/booking/BookWithVoyraModal.test.tsx`, red.
- [ ] **Step 3: Implement.** Update the stale comment at ~111 (clients load on Basic too).
- [ ] **Step 4: Run** green with 100 % coverage on the file.
- [ ] **Step 5: Commit** "Require a client on Book with Voyra and send CRM agencies to their Bookings list".

### Task F2: The Voyra card on the CRM booking detail

**Files:**
- Create: `components/booking/PlatformBookingCard.tsx` (+ test) — the body of `PlatformBookingDetail` (facts,
  itinerary or stay, supplier message, history, `TicketDownloadsCard`, actions, `TicketDownloadDialog`,
  `CancelPlatformBookingModal`, polling every 3 s while HELD, BOOKING, UNKNOWN or CANCELLING).
- Create: `components/booking/SetMarkupDialog.tsx` (+ test).
- Modify: `components/booking/PlatformBookingDetail.tsx` (page header and back link around the card),
  `components/booking/TicketDownloadDialog.tsx` (new prop `initialMarkup?: number`),
  `components/bookings/BookingManage.tsx` (~103-104, 250-284, 347-364), `lib/api/services/platformBookings.ts`
  (`setPlatformMarkup(id: string, markup: number): Promise<PlatformBookingResponse>` → `PATCH /api/platform-bookings/{id}/markup`),
  and the tests of each.

**Interfaces:**
- Produces: `PlatformBookingCard({ id, readOnly, onChange }: { id: string; readOnly?: boolean; onChange?: () => void })`.
  `onChange` fires after a cancel, a ticket download or a markup change so `BookingManage` reloads the booking and
  lifecycle. `SetMarkupDialog({ bookingId, publishedFare, initialMarkup, open, onClose, onDone })` with the same
  validation copy as `TicketDownloadDialog`. A CANCELLED row with `cancelRefund == null` shows "Cancelled, refund
  being settled by Voyra"; CANCELLING shows "Cancelling with Voyra". Actions: Cancel with Voyra (TICKETED, not readOnly, not ACCOUNTANT),
  Download ticket (as today), Set markup (TICKETED, not readOnly, not ACCOUNTANT); a refused action shows the
  server message in the card. `TicketDownloadDialog` and `SetMarkupDialog` prefill the newest download's markup.
- `BookingManage` for a row with `platformBookingId`: drops "Manage in Platform Bookings" (header link and the
  copy at ~355-360); renders `<PlatformBookingCard id={booking.platformBookingId} readOnly={role === 'ACCOUNTANT'} onChange={reload}/>`
  when the role is OWNER or ACCOUNTANT, or when `booking.agentId === session.userId`; otherwise the fallback copy.
  After a Voyra cancel it shows "Voyra is updating this booking" until the reloaded status is CANCELLED (poll the
  booking every 2 s, at most 30 s).

- [ ] **Step 1: Write the failing tests:** card: `pollsWhileBooking`, `cancelOpensTheModalAndCallsOnChange`,
  `setMarkupCallsTheApiAndOnChange`, `accountantSeesNoActions`, `aRefusedActionShowsTheServerMessage`;
  dialog prefill: `ticketDialogStartsAtTheNewestMarkup`; BookingManage: `voyraRowShowsTheCard`,
  `anotherAgentsVoyraRowShowsTheFallback`, `noManageInPlatformBookingsLink`, `aVoyraCancelWaitsForTheCrmStatus`;
  PlatformBookingDetail keeps its existing tests (now through the card).
- [ ] **Step 2: Run** `rtk npx vitest run components/booking components/bookings`, red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** green, 100 % coverage on the touched files; `rtk tsc --noEmit`.
- [ ] **Step 5: Commit** "Show the Voyra status, cancel, tickets and markup on the CRM booking".

### Task F3: Lists, navigation, old links, home

**Files:**
- Modify: `components/bookings/BookingsList.tsx` (filters ~238-260, fetch ~139-147), `app/agency/layout.tsx` (~67),
  `app/agents/layout.tsx` (~45, 52), `app/accounts/layout.tsx` (~55), `app/{agency,agents,accounts}/platform-bookings/page.tsx`,
  `app/{agency,agents,accounts}/platform-bookings/[id]/page.tsx`, `components/booking/BookingTrackerModal.tsx` (~85-89),
  `components/home/AgencyHome.tsx` (~64, 67, 193, 204), `app/agency/booking-report/page.tsx` (~48), and their tests.

**Interfaces:**
- Consumes: `useEntitlements()`, `useVendors()` (system vendor via `isSystem`), `BookingTrackerModal`.
- Produces:
  - BookingsList: a toggle chip "Booked with Voyra" that sets `vendorId` to the system vendor's id (and clears it);
    polls `listBookingsPage` every 5 s while a listed row has `platformBookingId` and `bookingStatus === 'PENDING'`;
    reads `?track=` and mounts `BookingTrackerModal` with `detailHref={`${basePath}/platform-bookings`}`, reloading on
    change and clearing the param on close (same pattern as `PlatformBookingTable`).
  - `BookingTrackerModal`: "Open booking" goes to `${bookingsHref}/${tenantBookingId}` when the polled row has one,
    else `${detailHref}/${bookingId}`.
  - Navigation: hide the three Platform Bookings entries when `has('CRM_CORE')`.
  - `platform-bookings/page.tsx` (three roles): once entitlements are ready and CRM Core is present,
    `router.replace(`${base}/bookings`)`; else render as today.
  - `platform-bookings/[id]/page.tsx`: with CRM Core, fetch the row; `tenantBookingId` set means
    `router.replace(`${base}/bookings/${tenantBookingId}`)`, else render the detail.
  - AgencyHome and booking report: with CRM Core, links go to `/agency/bookings`, rows to
    `/agency/bookings/${tenantBookingId}` or `/agency/platform-bookings/${id}` when null.

- [ ] **Step 1: Write the failing tests:** `voyraChipFiltersBySystemVendor`, `pollsWhileAVoyraRowIsPending`,
  `trackParamOpensTheTracker`; tracker: `openBookingGoesToTheCrmRowWhenItExists`; layouts:
  `crmAgencyHidesPlatformBookings` (three roles), `basicAgencyKeepsIt`; pages: `listRedirectsForCrm`,
  `detailRedirectsToTheCrmBooking`, `detailPageStaysWhenThereIsNoCrmBooking` (Review Focus 5); home:
  `crmHomeLinksToBookings`, `rowWithoutCrmBookingLinksToThePlatformDetail`.
- [ ] **Step 2: Run** the touched test files, red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `rtk npx vitest run` (whole suite, thresholds on), `rtk tsc --noEmit`, `rtk npm run lint`
  (≤ 22 errors).
- [ ] **Step 5: Commit** "Point CRM agencies at one Bookings list and retire Platform Bookings from their menus".

### Task E: End-to-end, invariants, matrix and docs

**Files:**
- Modify e2e (`travel-crm/e2e/`): `platform-flight-booking.spec.ts`, `platform-hotel-booking.spec.ts`,
  `platform-ticket.spec.ts`, `booking-tracker.spec.ts`, `platform-flight-roundtrip.spec.ts`, `shell-and-guards.spec.ts`,
  `agency-home.spec.ts`, `subscriptions.spec.ts`; create `e2e/unified-bookings.spec.ts`.
- Modify: `.claude/skills/full-regression/scripts/invariants.py`, `.claude/skills/full-regression/scenario-matrix.md`
  (repo root `.claude`), root `CLAUDE.md` (not in git), `travel-crm/CLAUDE.md`, `travel-crm-backend/PROGRESS.md`,
  memory `b2b_platform_plan.md`.

**Interfaces:**
- `unified-bookings.spec.ts` (mock providers, Dummy QA owner and one agent): book a flight with Voyra (client
  created inline from the dialog), land on `/agency/bookings?track=`, see the row PENDING then CONFIRMED with
  vendor "Voyra Platform", open it, see the Voyra card, download a ticket with markup 500 and see the selling price
  move to published + 500, Set markup 0 and see it return, cancel with Voyra and see CANCELLED; an agent opening the
  owner's Voyra booking sees the fallback copy; mock clean failure (`app.flight-booking.mock.*` failure switch if
  the backend under test has it on, else skip with a reason) shows CANCELLED with the fail reason; a QA Basic
  agency still uses `/agency/platform-bookings` and sees a required client picker.
- Invariants (BOOKING_RULES): one CRM booking per platform row with a client in BOOKING, UNKNOWN, TICKETED,
  FAILED or CANCELLED (once its mirror is DONE); CRM status per the spec table when DONE; a FAILED row's CRM
  booking has no supplier invoice, no supplier payment and no cancellation money; a PENDING Voyra CRM booking has
  no invoice and no receipt; the existing "mirror DONE means CRM CANCELLED" check scoped to platform CANCELLED.
- Matrix rows UB1-UB10 covering the journeys above, plus PB4 / PH4 / TK rows updated for the new routes.

- [ ] **Step 1:** Update the existing specs to the new routes and write `unified-bookings.spec.ts`.
- [ ] **Step 2:** Run `.claude/run/ap-gates.sh` (backend verify, ITs via colima, jar restart with the mock providers,
  tsc, lint, build with the Turnstile test key, `next start` 3000-3004, Playwright `--timeout 60000`, invariants).
  Expected: all green; Playwright failures limited to the two known Turnstile-key agency-apply specs, and only if
  the build lacks the key.
- [ ] **Step 3:** Update invariants, matrix and docs (sections named above; root `CLAUDE.md` gets a "Unified
  bookings (2026-10-31)" bullet after "Customer e-ticket and owner bookings").
- [ ] **Step 4: Commit** in each repo: "Cover unified bookings end to end and document them".

After Task E: final whole-branch review, then the regression-tester agent in `pre-merge` mode. Nothing is pushed
until the owner says so.
