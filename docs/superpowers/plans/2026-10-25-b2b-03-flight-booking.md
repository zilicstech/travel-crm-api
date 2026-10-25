# B2B Platform — Slice 3: Flight booking end to end — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An approved agency (owner, or an agent who handles flights) searches flights at its own discounted price, books through Voyra's single Tripjack account paying from its wallet, gets PNR and ticket numbers, sees commission credited, can cancel the whole booking for a refund, and — when a CRM client is chosen — gets a read-only mirror booking in its CRM; platform admin sees every booking with Voyra's net and can resolve UNKNOWN ones.

**Architecture:** One public table `platform_booking` (+ `platform_booking_event`) owns the supplier and money state; `PlatformBookingService` is the state machine (REVIEWING → HELD → BOOKING → TICKETED | FAILED | UNKNOWN → CANCELLED) and the only caller of `WalletService` for bookings. Supplier calls go through a new `FlightBookingProvider` interface with a `MockFlightBookingProvider` (default; switches for reprice, fare-expired, pending polls, timeout) and a best-effort `TripjackFlightBookingProvider` (endpoint shapes from spec §7.3, to be corrected by the slice 0 spike). Pricing is `PricingMath` + `AgencyPricingService.resolve` stamped into `pricing_snapshot` at review. The CRM mirror is written in a separate `REQUIRES_NEW` tenant-scoped bean after the debit commits; a repair job retries failed mirrors.

**Tech Stack:** Spring Boot 3.3 / Java 17 / Flyway / `RestClient` + `MockRestServiceServer` / Mockito; Next.js 15 / React 19 / `components/ui` / Playwright.

**Spec:** `/Users/shivanshsrivastava/Projects/Voyra global/B2B_PLATFORM_DESIGN.md` §5.1 (pricing engine), §6 (platform_booking, platform_booking_event, tenant side), §7.3 (flight booking), §7.5 (cancellation), §9 (Platform Bookings, search results, wizard), §10, §12 (invariants and tests).

**Deviations (rulings):**
- **Slice 0 not done.** No real Tripjack booking fixtures exist. The Tripjack booking provider is written to the spec's endpoint table with defensive parsing and `MockRestServiceServer` tests built from those shapes; the mock provider is the default (`app.flight-booking.provider=mock`) and runs every regression. Cost if wrong: the Tripjack provider's field mapping is corrected after the spike.
- **Strike-through price.** Spec §0 and §9 show a struck-through published fare; §5.1 says gross is "never shown". Ruling: show the published fare (gross) struck through, never Voyra's earned/net or review ids — the agency can derive gross from displayed ÷ (1 − discount) anyway. Cost if wrong: drop one line in the UI.
- **Quick book stays.** Spec §12 replaces the CRM "Quick book" flows with the wizard. Ruling: keep Quick book (it logs a CRM booking bought elsewhere — still a real use) and add **Book with Voyra** next to it; the two never share state. Cost if wrong: remove the Quick book button later.
- **Payment mode.** `PaymentMode.WALLET` already exists for the client advance wallet. The mirror's supplier bill uses a new `PaymentMode.VOYRA_WALLET` posting to the seeded ledger account "Voyra Wallet (advance to supplier)".
- **Voyra cancellation fee** is 0 (spec §13 default) — a platform setting `voyra_cancel_fee` (INR, default 0) so it can change without a release.

**Branch:** `feat/b2b-platform` in both repos. Path shorthand as slice 2 (`BE`, `BT`, `PM`, `TM` = tenant migrations, `FE`).

## Global Constraints

- Everything in the slice 1 and slice 2 Global Constraints holds (blueprint audit, `@Schema` on every DTO field, ids via `UniqueIdResolver`, money `BigDecimal`/`NUMERIC(19,2)`, `SecurityContextUtil`, `UI_BLUEPRINT.md`, commit trailers).
- Next public migration **V19**; next tenant migration **V78**.
- Agencies never see: Voyra's earned commission, `voyra_net`, review/supplier ids, raw supplier payloads, the Tripjack credential. Agency DTOs are hand-built and a controller test asserts those keys are absent from the raw JSON.
- `book` is sent **at most once per `supplier_review_id`** (`UNIQUE`); a timeout never retries book — the row moves to BOOKING and the poller asks `bookingDetails`.
- Wallet calls only through `WalletService` (slice 2): `hold`, `releaseHold`, `debitHeld`, `credit(COMMISSION_CREDIT|REFUND_CREDIT)`, `debitOrReceivable(TDS_DEDUCTED|COMMISSION_RECALL, …)`. Keys: hold `pb-hold:<id>`, release `pb-release:<id>`, debit `pb-debit:<id>`, commission `pb-comm:<id>`, TDS `pb-tds:<id>`, refund `pb-refund:<id>`, recall `pb-recall:<id>`.
- A UAT booking (`environment = UAT`) may hold/debit only a wallet with `isTest = true` (409 `TEST_WALLET_REQUIRED`).
- Who may book: `AGENCY_OWNER`, or `AGENT` whose `manageableServices` contains `FLIGHT` (403 otherwise). Accountant: read only.
- Passenger PII in `platform_booking.pax_enc` is AES-encrypted with `PasswordEncryptionUtil` (key `app.security.password-encryption-key`); events store redacted payloads (no apikey, passport numbers masked to last 3).
- Every booking with `voyra_net < 0` calls `NotificationService.platformAlert`.

## Review Focus

1. **Double click on Confirm / two tabs** — one hold, one book call, one debit; second confirm returns the same booking. Test in Task 4.
2. **Price changed at review** (supplier repriced, or a new pricing row came into force) — the agency must re-confirm the new displayed price before any hold. Test in Task 4.
3. **Wallet short at confirm** — 409 `INSUFFICIENT_BALANCE` with available, no supplier book call, row stays REVIEWING. Test in Task 4.
4. **Supplier times out on book then tickets later** — row BOOKING → poller finds TICKETED → one debit; a TICKETED arriving after an admin "Resolve as failed" still debits (receivable if short). Test in Task 5.
5. **Agency A opens agency B's booking id** — 404. Test in Task 7.

---

### Task 1: Migrations, entities, pricing resolve-or-refuse, pending-rate withdraw

**Files:**
- Create: `PM/V19__b2b_platform_booking.sql`, `TM/V78__voyra_platform_vendor_and_wallet_account.sql`
- Create: `BE/entity/{PlatformBooking,PlatformBookingEvent}.java`, repositories, enums `PlatformBookingStatus` (REVIEWING, HELD, BOOKING, UNKNOWN, TICKETED, FAILED, CANCELLED, ABORTED), `MirrorStatus` (NONE, PENDING, DONE, FAILED), `VoyraEarnedSource` (API, ESTIMATE, STATEMENT), `SupplierEnvironment` reuse if one exists
- Modify: `BE/enums/PaymentMode.java` (+`VOYRA_WALLET`), `BE/entity/Booking.java` (+`platformBookingId`), `BE/service/AgencyPricingService.java` (+`requireInForce(tenantId, product)` throwing `PricingNotConfiguredException` → 409 `PRICING_NOT_CONFIGURED`; +`withdraw(tenantId, pricingId)` for a row not yet approved — deletes nothing, sets `withdrawn_at/by`, audited `PRICING_WITHDRAW`), `PM/V19` adds `agency_pricing.withdrawn_at/withdrawn_by`, `resolve` ignores withdrawn rows; `PlatformAgencyPricingController` gains `POST /{pricingId}/withdraw`
- Modify: `PlatformSettingService` (+`voyra_cancel_fee` default `0`)
- Test: `AgencyPricingServiceTest` (+`requireInForceRefusesWhenNoRow`, `withdrawnRowIsNeverInForce`, `approvedRowCannotBeWithdrawn`), migration applies (verify run)

**Interfaces:**
- `platform_booking` columns per spec §6, plus: `product VARCHAR(8)`, `client_id`, `lead_id`, `service_id`, `issued_by_agent_id` (all nullable tenant ids), `abort_reason`, `supplier_message VARCHAR(500)`, `fare_rules TEXT`, `review_expires_at`, `cancel_refund`, `cancelled_at`, `last_polled_at`, `poll_count INT`, `idempotency_key VARCHAR(80) UNIQUE` (client key); `supplier_review_id UNIQUE`; indexes `(tenant_id, created_at DESC)`, `(status)`.
- V78 (tenant, idempotent): insert vendor "Voyra Platform" if no vendor of that name; insert ledger account "Voyra Wallet (advance to supplier)" (asset, under the supplier-advance group the chart uses — match how `ChartOfAccountsSeedService` defines accounts) if absent; `ALTER TABLE booking ADD COLUMN platform_booking_id VARCHAR(36) UNIQUE`.
- Produces: `PlatformBookingRepository.findByIdAndTenantId`, `findByIdForUpdate`, `findBySupplierReviewId`, `findByIdempotencyKey`, `findByStatusIn(…)`, `findByTenantIdOrderByCreatedAtDesc(Pageable)`, `findAllByOrderByCreatedAtDesc(Pageable)`, `existsByStatusIn(…)`.
- [ ] Steps: failing tests → implement → `rtk ./mvnw verify` → commit `feat(booking): platform booking tables, Voyra vendor and wallet account, pricing resolve-or-refuse and withdraw`.

### Task 2: Flight booking provider (interface, mock, Tripjack)

**Files:**
- Create: `BE/service/booking/FlightBookingProvider.java`, `MockFlightBookingProvider.java`, `TripjackFlightBookingProvider.java`, `FlightBookingProviderConfig.java` (`app.flight-booking.provider` = `mock` default | `tripjack`), models `BE/models/booking/{FareReview,BookRequest,BookResult,SupplierBookingStatus,CancelQuote,CancelResult,PaxDetail}.java`
- Modify: `application.properties` (`app.flight-booking.provider=${FLIGHT_BOOKING_PROVIDER:mock}`, `app.flight-booking.mock.reprice-pct=0`, `…mock.fare-expired=false`, `…mock.pending-polls=0`, `…mock.book-timeout=false`, `app.flight-booking.poll.max-minutes=30`)
- Test: `MockFlightBookingProviderTest`, `TripjackFlightBookingProviderTest` (`MockRestServiceServer`)

**Interfaces:**
- `FareReview review(String offerId, int adults, int children, int infants)` → `FareReview(String reviewId, BigDecimal totalFare, BigDecimal baseFare, String currency, String fareRules, Instant expiresAt, JsonNode itinerary)`; throws `FareUnavailableException` when the fare expired.
- `BookResult book(BookRequest r)` → `BookResult(String supplierBookingId, SupplierBookingStatus status /*TICKETED|PENDING|FAILED*/, String pnr, List<String> ticketNumbers, String message)`; a transport timeout throws `SupplierTimeoutException` (never caught by the provider).
- `BookResult bookingDetails(String supplierBookingId)`; `CancelQuote cancelQuote(String supplierBookingId)` → `(BigDecimal refund, String message)`; `CancelResult cancel(String supplierBookingId)` → `(boolean accepted, BigDecimal refund, String message)`.
- Mock: deterministic ids from offerId; `reprice-pct` raises `totalFare`; `fare-expired` throws on review; `pending-polls=n` returns PENDING n times then TICKETED; `book-timeout` throws on book but `bookingDetails` returns TICKETED; refund = 80 % of total fare. Base fare = 85 % of total for mock offers.
- Tripjack endpoints (spec §7.3): `POST /fms/v1/review {priceIds:[offerId]}` → `bookingId` (review id), `totalPriceInfo.totalFareDetail.fC.TF/BF`; `POST /oms/v1/air/book {bookingId, paymentInfos:[{amount: TF}], travellerInfo:[…], deliveryInfo:{emails,contacts}}`; `POST /oms/v1/booking-details {bookingId}` → `order.status`, `itemInfos.AIR.travellerInfos[].pnrDetails/ticketNumberDetails`; `POST /fms/v2/farerule`; `POST /oms/v1/air/amendment/amendment-charges` and `/submit-amendment` for cancel. Header `apikey`; credential from `PlatformSupplierCredentialService.resolveActive`. Every field read defensively; class Javadoc says "unverified until the slice 0 spike".
- [ ] Steps: tests first (mock switches; Tripjack request body shapes and response parsing incl. `success:false` → FAILED with message, HTTP timeout → `SupplierTimeoutException`) → implement → verify → commit `feat(booking): flight booking provider with mock and Tripjack implementations`.

### Task 3: Priced search results

**Files:**
- Create: `BE/service/booking/AgencyPriceQuoter.java`
- Modify: `FlightOfferResponse` (+`publishedFare`, `displayedFare`, `agencyEarns`, `bookable`, `notBookableReason`), `FlightGroupResponse` fare options likewise, `FlightSearchService` (decorate every offer after the provider answers; mock offers need a `baseFare` — add `baseFare` to the internal offer model, Tripjack parses `fd.ADULT.fC.BF`)
- Test: `AgencyPriceQuoterTest`, `FlightSearchServiceTest` (+decoration)

**Interfaces:**
- `PriceQuote quote(String tenantId, PricingProduct product, BigDecimal gross, BigDecimal base)` → `PriceQuote(BigDecimal displayed, BigDecimal agencyEarns /*commission − TDS*/, BigDecimal voyraEarned, BigDecimal agencyCommission, BigDecimal tds, BigDecimal voyraNet, String pricingId, BigDecimal discountPct, BigDecimal commissionPct)` using `PricingMath`, `PlatformSettingService.discountBase/commissionEstimatePct/tdsRate`, `AgencyPricingService.resolve(…, now)`; commission capped at `voyraEarned` unless the row allows more (spec §5.1).
- No pricing row → offers still listed with `bookable=false`, `notBookableReason="PRICING_NOT_CONFIGURED"`, `displayedFare = publishedFare`, `agencyEarns = 0`. Super admin searches (no tenant) are undecorated.
- [ ] Tests: `displayedIsGrossLessDiscountOnGross` (₹5,000, 10 % → ₹4,500 — the owner's example), `commissionCappedAtWhatVoyraEarns`, `tdsDeductedFromAgencyEarns` (tds_rate 5 %), `noPricingRowMeansNotBookable`, controller JSON for an agency has no `voyraEarned`/`voyraNet` keys → implement → verify → commit `feat(booking): agency-priced flight search results`.

### Task 4: `PlatformBookingService` — review and confirm (hold + book once)

**Files:**
- Create: `BE/service/booking/PlatformBookingService.java`, `PlatformBookingEventRecorder.java`, `PaxCodec.java` (encrypt/decrypt + redaction), DTOs `FlightReviewRequest{offerId, adults, children, infants, clientId?, leadId?, serviceId?}`, `PlatformBookingReviewResponse{bookingId, displayedFare, previousDisplayedFare?, priceChanged, agencyEarns, fareRules, reviewExpiresAt, walletAvailable}`, `PlatformBookingConfirmRequest{idempotencyKey, acceptedDisplayedFare, passengers: List<PaxInput>, contactEmail, contactPhone, issuedByAgentId?}`, `PaxInput{type ADULT|CHILD|INFANT, title, firstName, lastName, dob?, passportNo?, passportExpiry?, nationality?, memberId?}`
- Test: `PlatformBookingServiceTest`

**Interfaces:**
- `review(FlightReviewRequest)` → creates REVIEWING row (tenant, product FLIGHT, environment + credential id from the active credential or `MOCK` when the mock provider is active, `supplier_review_id`, gross/base from the review, pricing snapshot from `AgencyPriceQuoter`); refuses `PRICING_NOT_CONFIGURED`, `booking_blocked`, SUSPENDED (423 via interceptor already), DUE receivable (409 `RECEIVABLE_DUE`) before calling the supplier.
- `confirm(String bookingId, PlatformBookingConfirmRequest)`:
  1. lock row; same `idempotencyKey` already used → return current state; different key on a non-REVIEWING row → 409.
  2. `acceptedDisplayedFare` ≠ stamped displayed → 409 `PRICE_CHANGED` with the new figure (re-confirm). Also re-resolve pricing: if a different row is now in force, re-stamp and 409 `PRICE_CHANGED`.
  3. review expired → 409 `REVIEW_EXPIRED`.
  4. UAT + non-test wallet → 409 `TEST_WALLET_REQUIRED`.
  5. `walletService.hold(tenant, displayed, "pb-hold:"+id, "PLATFORM_BOOKING", id)` → HELD (commit this step: hold runs REQUIRES_NEW).
  6. status BOOKING, commit, then `provider.book(...)` **outside** the row lock transaction; result handled by `applySupplierResult(id, BookResult)`; `SupplierTimeoutException` → stays BOOKING (poller takes over).
- `applySupplierResult` (package-visible, used by Task 6 too): TICKETED → `walletService.debitHeld(holdId, displayed, "pb-debit:"+id, …)`; if `agencyCommission > 0` → `credit(COMMISSION_CREDIT, agencyCommission, "pb-comm:"+id)` and, if `tds > 0`, `debitOrReceivable(TDS_DEDUCTED, tds, "pb-tds:"+id, …)`; store PNR/tickets, `ticketed_at`; variance (supplier amount ≠ reviewed) recorded on `voyra_net` + alert; `voyra_net < 0` alert; then schedule mirror (Task 8 hook, no-op until then). FAILED → `releaseHold`, store supplier message. PENDING → stays BOOKING.
- Every transition records a `platform_booking_event` (redacted payload).
- [ ] Tests (Review Focus 1–3 plus): `reviewStampsPricingSnapshot`, `confirmTwiceWithTheSameKeyBooksOnce` (provider.book called once), `priceChangedAtReviewNeedsReconfirm`, `newPricingRowInForceNeedsReconfirm`, `shortWalletIs409AndNoSupplierCall`, `uatBookingOnANonTestWalletIsRefused`, `ticketedDebitsAndCreditsCommission`, `ticketedWithTdsPostsTdsEntry`, `failedReleasesTheHold`, `timeoutLeavesBooking`, `agentWithoutFlightServiceIs403` → implement → verify → commit `feat(booking): review and confirm with a wallet hold and a single supplier book call`.

### Task 5: Poller, UNKNOWN, admin resolve, abort, credential-switch guard

**Files:**
- Create: `BE/scheduler/PlatformBookingPollJob.java` (`app.flight-booking.poll.cron=${APP_FLIGHT_BOOKING_POLL_CRON:0 * * * * *}`), `BE/service/booking/PlatformBookingPoller.java`
- Modify: `PlatformBookingService` (+`abort(id)` for HELD/REVIEWING: release hold → ABORTED; +`resolve(id, outcome TICKETED|FAILED, pnr?, tickets?, note)` admin-only for UNKNOWN/BOOKING), `PlatformSupplierCredentialService` (setting/activating a credential refused 409 while any row is HELD/BOOKING/UNKNOWN)
- Test: `PlatformBookingPollerTest`, `PlatformBookingServiceTest` additions, `PlatformSupplierCredentialServiceTest` addition

**Rules:** each BOOKING row polled `bookingDetails` in its own transaction; terminal answer → `applySupplierResult`; no terminal answer after `poll.max-minutes` → UNKNOWN (hold kept) + alert; UNKNOWN rows keep being polled every 10 minutes; a TICKETED arriving after an admin resolved FAILED (hold already released) → `debitHeld` cannot be used, so debit with `debitOrReceivable(BOOKING_DEBIT, displayed, "pb-debit:"+id, …, LATE_TICKET)` + commission as normal + alert.
- [ ] Tests: `pendingThenTicketedOnPollDebitsOnce`, `noAnswerAfterMaxMinutesBecomesUnknownAndKeepsHold`, `adminResolveTicketedDebits`, `adminResolveFailedReleases`, `lateTicketAfterResolveFailedDebitsViaReceivable`, `abortHeldReleasesTheHold`, `credentialSwitchBlockedWhileBookingsInFlight` → implement → verify → commit `feat(booking): poll pending bookings, UNKNOWN with admin resolve, abort, guarded credential switch`.

### Task 6: Cancellation (whole booking)

**Files:**
- Modify: `PlatformBookingService` (+`cancelQuote(id)` → `{refund, recall, net, voyraFee}` and `cancel(id, idempotencyKey)`)
- Test: `PlatformBookingCancelTest`

**Rules (spec §7.5):** only TICKETED; supplier refund `R` from `provider.cancel`; `refund = max(0, R − voyraFee)` capped at `displayed`; `recall = agencyCommission − tds` (what was credited net); post `credit(REFUND_CREDIT, refund, "pb-refund:"+id)` then `debitOrReceivable(COMMISSION_RECALL, recall, "pb-recall:"+id, …, CANCEL_NET_NEGATIVE)`; `voyra_earned_reversal = voyra_earned`; re-derive `voyra_net`; status CANCELLED, `cancel_refund`, `cancelled_at`; mirror cancel hook (Task 8). Supplier refuses → 409 with its message, nothing posted.
- [ ] Tests: `cancelPostsRefundThenRecall`, `recallShortfallOpensAReceivableAndBlocksBooking`, `supplierRefusalPostsNothing`, `onlyTicketedCanBeCancelled`, `cancelTwiceWithTheSameKeyPostsOnce`, `voyraNetRecomputedWithReversal` → implement → verify → commit `feat(booking): whole-booking cancellation with refund and commission recall`.

### Task 7: Booking APIs (agency + platform)

**Files:**
- Create: `BE/controller/PlatformBookingController.java` (`/api/platform-bookings`), `BE/controller/PlatformAdminBookingController.java` (`/api/platform/bookings`), `BE/service/booking/PlatformBookingQueryService.java`, DTOs `PlatformBookingResponse` (agency view: id, product, status, pnr, ticketNumbers, displayedFare, agencyEarns, itinerary summary, passengers (names + type only), bookedByName, clientName?, createdAt, ticketedAt, cancelRefund, supplierMessage, mirrorStatus, tenantBookingId), `PlatformAdminBookingResponse` (adds tenantId, agencyName, environment, gross, base, discountPct, voyraEarned, voyraEarnedSource, agencyCommission, tds, voyraNet, events)
- Test: `PlatformBookingControllerTest`, `PlatformAdminBookingControllerTest`, query service test

**Endpoints:** agency — `POST /flights/review`, `POST /{id}/confirm`, `POST /{id}/abort`, `GET /{id}/cancel-quote`, `POST /{id}/cancel`, `GET` (paged, agents see only their own bookings unless owner/accountant), `GET /{id}`; roles `AGENCY_OWNER`, `AGENT` (FLIGHT check in the service), `ACCOUNTANT` GET only. Platform — `GET` (paged, filter status/tenant), `GET /{id}`, `POST /{id}/resolve`.
- `/api/platform-bookings/**` must be allowed for SUSPENDED reads and refused for writes (existing interceptor handles it) — confirm in a test.
- [ ] Tests: agency JSON never contains `voyraEarned`, `voyraNet`, `gross`, `supplierReviewId`, `supplierBookingId` (raw string assert); agency B gets 404 on agency A's id; agent sees only own list; accountant POST 403; platform resolve 200 for super admin → implement → verify → commit `feat(booking): agency and platform booking APIs`.

### Task 8: CRM mirror, read-only guards, repair job, mirror cancel

**Files:**
- Create: `BE/service/booking/PlatformBookingMirrorService.java` (`REQUIRES_NEW`, tenant context switched and restored per blueprint §3.5), `BE/scheduler/PlatformBookingMirrorRepairJob.java`
- Modify: `BookingService`, `BookingDocumentService`, `BookingCancellationService`, `SupplierPaymentService` (409 "Managed from Platform Bookings" when the booking has `platformBookingId`, except calls made by the mirror service with the system actor), `BookingCompletionJob`/`BookingCompletionService` (skip mirrors until the platform row is terminal), `AuthorResolver`/`SecurityContextUtil` (a system principal usable by platform-driven tenant writes — `SystemActor.run(tenantId, Runnable)` or equivalent), `PlatformBookingService` hooks (after TICKETED and after CANCELLED)
- Test: `PlatformBookingMirrorServiceTest`, guard tests in each modified service's test

**Rules (spec §6 tenant side):** mirror only when `client_id` is set; booking CONFIRMED, vendor "Voyra Platform", client, agent = booking user if AGENT else `issued_by_agent_id` else the lead's agent; PNR, travellers, sectors from the itinerary; one `E_TICKET` document (`purchase_currency INR`, `purchase_price = displayed`, base/tax split from the fare, markup = agency default); its supplier bill marked paid with `PaymentMode.VOYRA_WALLET` crediting "Voyra Wallet (advance to supplier)" (never Bank 1110); `mirror_status` DONE / FAILED (+ repair job every 10 min retries FAILED/PENDING). Cancel mirror: system actor calls the existing cancel with type CANCELLATION, `airlineCharge 0`, `supplierCharge = displayed − R`, `agencyFee 0`, and records the supplier refund against "Voyra Wallet (advance to supplier)".
- [ ] Tests: `mirrorCreatedOnlyWithAClient`, `mirrorSupplierBillPostsToVoyraWalletNot1110`, `crmEditOfAMirrorIs409`, `mirrorFailureLeavesMoneyAndMarksFailed`, `repairJobRetriesFailedMirrors`, `mirrorCancelCreatesCreditNoteAndRestoresTenantContext` (run from a no-principal context), `completionJobSkipsLiveMirrors` → implement → verify → commit `feat(booking): read-only CRM mirror of platform bookings with repair and cancel`.

### Task 9: Frontend — priced results and the Book with Voyra wizard

**Files:**
- Create: `FE/lib/api/services/platformBookings.ts`, types in `FE/lib/api/types.ts`, `FE/components/booking/{BookWithVoyraModal,PassengerForm,FareRulesPanel,BookingStatusPanel}.tsx`
- Modify: `FE/components/flights/FlightSearchPanel.tsx` (each fare shows published fare struck through, displayed fare, "You earn ₹X"; **Book with Voyra** button when `bookable`, disabled with tooltip "Pricing not set up — contact Voyra" otherwise; Quick book stays), the lead service tab flight search (pass `leadId`/`serviceId`/client so the wizard pre-selects the lead's client and travellers)
- Wizard steps: (1) Passengers — CRM client + traveller picker (existing pickers) or ad-hoc passengers; contact email/phone; agent picker "Issued by" for owners when a client is chosen; (2) Fare rules + review call → shows displayed, earns, wallet available, expiry countdown; price-changed banner requiring "Accept new price"; (3) Confirm → `idempotencyKey` generated once per wizard open; insufficient balance → "Top up" link to `/agency/wallet`; (4) Status — polls `GET /{id}` every 3 s until TICKETED/FAILED/UNKNOWN (UNKNOWN shows "We're confirming with the airline; your money is on hold, not spent").
- [ ] `rtk tsc`, `rtk lint` (22 baseline), `next build`, Chrome as owner and as a flight agent at 1400/390 against the mock provider (book one ticket end to end on the Dummy QA test wallet after the admin sets a FLIGHT pricing row) → commit `feat(booking): priced flight results and the Book with Voyra wizard`.

### Task 10: Frontend — Platform Bookings (agency + admin), CRM badge

**Files:**
- Create: `FE/app/agency/platform-bookings/{page.tsx,[id]/page.tsx}`, agent route equivalent under `FE/app/agents/platform-bookings/`, `FE/app/platform/bookings/{page.tsx,[id]/page.tsx}`, `FE/components/booking/{PlatformBookingTable,CancelPlatformBookingModal,ResolveBookingModal}.tsx`
- Modify: agency + agent nav (Platform section: Platform Bookings), platform nav (Platform Bookings with UNKNOWN count badge), CRM bookings list + booking page (`Voyra` badge, PNR, edit/cancel/payment actions hidden when `platformBookingId` is set; a link "Manage in Platform Bookings")
- Detail: status timeline (events as plain-language lines for the agency; full events for admin), PNR, tickets, passengers, fare + earns, cancel with quote (refund, commission recalled, net to wallet); admin detail adds gross/base/discount/earned/commission/TDS/voyraNet/environment and Resolve for UNKNOWN/BOOKING.
- [ ] tsc/lint/build, Chrome checks (owner, agent, accountant read-only, super admin) → commit `feat(booking): Platform Bookings for agencies and admins; Voyra badge on CRM bookings`.

### Task 11: E2E, invariants, docs

**Files:**
- Create: `FE/e2e/platform-flight-booking.spec.ts`
- Modify: `invariants.py` (spec §12 booking invariants: TICKETED ⇒ hold closed ∧ one BOOKING_DEBIT ∧ commission iff `agency_commission > 0`; FAILED after HELD ⇒ HOLD_RELEASE; UNKNOWN ⇒ hold open; CANCELLED ⇒ refund ≤ debit ∧ recall = credited or DUE receivable; `voyra_net` = earned − (gross − displayed) − commission (− reversal)); `scenario-matrix.md` (PB rows: owner books, agent books, agent without FLIGHT refused, price change re-confirm, short wallet, timeout→poll→ticketed, admin resolve, cancel → refund + recall, mirror created with client / absent without, CRM edit of mirror refused, accountant read-only); docs (`CLAUDE.md` root + FE, `PROGRESS.md`, PR texts — slice 3 section, deploy notes: `FLIGHT_BOOKING_PROVIDER=tripjack` only after slice 0, poll cron, `voyra_cancel_fee`)
- Spec: as `PLATFORM_ADMIN` set a FLIGHT pricing row (10 % / 2 %) for Dummy QA if none in force; as OWNER top up via stub (wallet is test) → search mock flights → assert displayed = published × 0.9 on screen and no `voyraNet` in the API body → book with ad-hoc passengers → TICKETED with PNR → statement shows HOLD, HOLD_RELEASE, BOOKING_DEBIT, COMMISSION_CREDIT → cancel → REFUND_CREDIT + COMMISSION_RECALL; as an agent with FLIGHT book one with a CRM client → mirror booking visible in CRM with `Voyra` badge and no edit; as an agent without FLIGHT → Book hidden / API 403.
- [ ] `rtk ./mvnw verify`, full Playwright suite on a fresh production build, invariants `ALL INVARIANTS HOLD` → commit both repos `test(booking): platform flight booking e2e, invariants and docs`.
