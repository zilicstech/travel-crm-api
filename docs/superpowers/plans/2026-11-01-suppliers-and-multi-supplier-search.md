# Suppliers in the Database and Multi-Supplier Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Suppliers become database rows with a search and a booking toggle per product, every search-enabled supplier
is searched in parallel and shown side by side, the agent books on the supplier they pick, and Voyra's commission,
earnings, P&L and statements leave the product.

**Architecture:** Public migration V37 (expand only) adds the toggles, lets several rows share an integration and turns
the mock into a supplier row. Every provider bean always exists and takes the supplier row (`ResolvedCredential`) per
call; a new `SupplierRegistry` picks rows and beans (search: every search-enabled row; booking: the row stamped on the
booking). Supplier-scoped ids are namespaced `<supplierId>:<provider id>` at the search boundary and parsed at review.
`FlightResultPaging` groups one flight across suppliers by a canonical key and recomputes each row's cheapest bookable
fare at page time.

**Tech Stack:** Spring Boot 3.3 / Java 17, JPA, Flyway, JUnit 5 + Mockito, Testcontainers, JaCoCo 100 %; Next.js 15 /
React 19, Vitest (100 % thresholds), Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-11-01-suppliers-plans-and-multi-supplier-search-design.md`,
**Parts 1 and 2** plus their share of Cross-cutting and Deploy notes. Part 3 (Plans tab, Custom plan, frozen prices) is
`docs/superpowers/plans/2026-11-01-settings-and-plan-prices.md`, running in parallel in another worktree. Why the
rulings are what they are: the review notes folded into the spec.

Repos: backend `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend`, frontend
`/Users/shivanshsrivastava/Projects/Voyra global/travel-crm`, on the branch or worktree the executor was given. Backend
paths are under `src/main/java/com/voyra/crm/` unless they start with `src/`; frontend paths are relative to
`travel-crm/`. `/Users/shivanshsrivastava/Projects/Voyra global/.claude/` and the root `CLAUDE.md` are not in git:
edit them, never commit them.

## Global Constraints

- Never push; never touch `main`. Commit per task in the repo the task changed, prose message, ending with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Qq6uXt8Qha7QvtoJUmYRa9
  ```
- Prefix shell commands with `rtk`. Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"`.
  Each backend task ends with `rtk ./mvnw -q test -Dtest=<its tests>` and
  `scripts/coverage-gaps.sh '<same pattern>' <touched class or package>` showing no missed line, branch or method.
- **Migration number is V37** (`V37__suppliers_in_database.sql`), expand only: no column, table, row or constraint
  that an old instance reads is dropped. V38 is the other stream's; the drops (`is_active`, `commission_estimate_pct`,
  `allow_above_earned`, the retired setting rows) are V39 in a later release, not here.
- Hands off (the other stream edits them): `SubscriptionPlan`, `SubscriptionService`, `SubscriptionBillingService`,
  `EntitlementService`'s plan branches, `PlatformAgencySubscriptionController`, `AgencySubscriptionController`,
  `components/platform/AgencySubscriptionCard.tsx`, `app/agency/subscription/*`, `components/platform/SettingsTabs.tsx`,
  `app/platform/settings/page.tsx`, `app/platform/settings/layout.tsx`. In `PlatformSettingService` remove only the
  retired keys (`tripjack_live_ready`, `commission_estimate_pct`, `discount_base`, `reference_fare_basket`,
  `reference_fare_basket_hotel`); never touch `sku_prices`. In `ControllerSkuTable` only remove the
  `PlatformPnlController` and `PlatformStatementController` entries. In `e2e/platform-supplier.spec.ts` leave the test
  "General has the Go live card and the booking rules, and no JSON settings" alone (the other plan deletes it).
- Supplier hosts: TRIPJACK UAT `apitest.tripjack.com`, PROD `api.tripjack.com` (stored as `https://<host>`); the hotel
  hosts stay derived from the environment (`app.hotel-supplier.tripjack.uat-base-url` / `prod-base-url`). MOCK takes
  `mock://local` only, environment MOCK. Supplier name at most 80 characters, unique ignoring case.
- API keys: encrypted, write-only, masked to the last 4 (`"••••••" + last4`, `"••••••"` for 4 or fewer, blank
  included); never in an audit detail, a log line or an exception message.
- Search fan-out overall deadline **40 s**; executor 8 threads, queue 64, a rejected task is a failed supplier.
- Exact error codes and copy:
  - 409 `NO_SUPPLIER` "No supplier is enabled for flight search" / "No supplier is enabled for hotel search"
  - 503 `SUPPLIERS_UNAVAILABLE` "Suppliers are not responding; try again in a minute"
  - 400 `OFFER_UNAVAILABLE` "This fare is no longer available; search again" (hotel detail: "This hotel is no longer
    available; search again")
  - 400 `MIXED_SUPPLIERS` "Both legs must be on one supplier" (hotel: "The hotel and the room must be on one supplier")
  - 409 `SUPPLIER_NOT_BOOKABLE` "`<name>` is not taking bookings right now; pick another supplier"
  - 400 `PROVIDER_UNAVAILABLE` "The mock supplier cannot be added on this server"
  - 400 "Add a new supplier for the production account"; 400 "The integration of a supplier cannot change";
    409 `SUPPLIER_EXISTS` "A supplier named `<name>` already exists"
- Exact UI copy: "`<name>` did not respond" (several: names joined with ", "), "+`N` more", "Book on `<name>`",
  "Not taking bookings", "Return flights on `<name>` (same supplier as your onward)",
  "No return flights on `<name>`; pick an onward fare on another supplier", "Booking on `<name>`".
- `discount_base` is GROSS from now on (no reader); a commission rule pays exactly its percentage of base (no cap).
  TDS on agency commission stays (`tds_rate`, `pb-tds`). Wallet keys, state machine, CRM mirror and e-ticket do not
  change.
- Frontend: `rtk npx vitest run <files>` per task. Before Task 10 run `rtk npm run lint` once and note its error count;
  it must not rise. UI follows `travel-crm/UI_BLUEPRINT.md` and `components/ui` primitives.

## Rulings made while planning

1. **The V37 mock row has an empty stored key** (`api_key_encrypted = ''`). SQL cannot encrypt with the application's
   AES key, so a blank stored key means "no key": it decrypts to `""` and masks to `"••••••"`. Rotating it through the
   API stores an encrypted key as usual.
2. **V37 sets `commission_estimate_pct` DEFAULT 5,** because the new entity stops writing that NOT NULL column and an
   old instance still reads it during the rolling deploy (5 is its old fallback).
3. **The flight-number part of the group key strips the airline code first.** The spec says "digits of flightNumber",
   but the mock emits `6E-206` and Tripjack `206` with code `6E`; taking all digits would give `6206`. Normalisation
   lives in one helper instead of in every provider.
4. **Only a name, base URL or key edit bumps the supplier row's `updated_at`.** That timestamp is what expires an open
   review at confirm; a toggle edit bumps only its product row, so switching Hotel search does not expire flight
   reviews. The search cache key also changes on any toggle because the searchable set changes.
5. **The offer's existing `supplier` string is set to the supplier row's name** next to the new `supplierName`, so the
   CRM quick book and lead proposals record the supplier the agent saw.
6. **Partial hotel results are kept under their `searchId`** (with their `suppliersFailed`) so pages 2+ slice the same
   list; the "not cached" rule is applied to the flight cache. A new hotel search always fans out again.
7. **`SupplierStartupLogger` is deleted;** `SupplierRegistry` logs the enabled rows at startup and warns once per row
   whose integration is not in the build.
8. **The e2e global setup switches every other supplier's search off and does not switch it back on**; a developer's
   local Tripjack row is turned on again from the Suppliers tab.

## Review Focus

1. Two rows of one integration (Mock supplier, Mock supplier 2) answer with identical provider ids. Expected: the ids
   stay distinct end to end and a booking on the second row is stamped with the second row. Pinned in Task 4
   `offersFromEverySupplierCarryItsIdAndNameAndNamespacedIds` and Task 7 `reviewStampsTheChosenSuppliersIdAndEnvironment`.
2. A provider id that itself contains `:` (Tripjack hotel option ids are opaque). Expected: parsing splits at the first
   colon only. Pinned in Task 4 `parsesAtTheFirstColonOnly`.
3. The same flight from two providers in different shapes (`6E-206`, `206` with code `6e`, `6E 0206`, `06:15:00`).
   Expected: one row. Pinned in Task 6 `twoProviderShapesOfOneFlightGroupIntoOneRow`.
4. An admin rotates a key while an agent sits on the review screen, or flips a toggle meanwhile. Expected: the key edit
   refuses the confirm as an expired review; a toggle-only edit does not. Pinned in Task 7
   `confirmOfAReviewOlderThanASupplierEditIsExpired` and `confirmAfterAToggleOnlyEditStillHolds`.
5. A supplier is switched on, off or renamed while a search sits in the 10-minute cache. Expected: the next page
   request misses the cache. Pinned in Task 4 `aToggleOrEditOfASupplierMissesTheCache`.

---

## Backend

### Task 1: Agency prices come from the pricing rules alone

**Files:**
- Modify: `service/booking/AgencyPriceQuoter.java`, `service/booking/PriceQuote.java`, `util/PricingMath.java`,
  `service/AgencyPricingService.java` (delete `impact`, `impactOf`, `preview`, `worst` and the loss and
  allow-above figures in `assign`, `toAssignmentResponse` and `ruleDetail`),
  `controller/PlatformPricingRuleController.java` (delete `GET /{ruleId}/impact`),
  `controller/PlatformAgencyPricingController.java` (delete `GET /preview`), `entity/PricingRule.java` (unmap
  `allowAboveEarned`), `dto/PricingRuleCreateRequest.java`, `dto/PricingRuleUpdateRequest.java`,
  `dto/PricingRuleResponse.java` (drop `allowAboveEarned`), `dto/AgencyPricingAssignmentResponse.java` (drop
  `worstCaseNetPer10k`, `lossMaking`), `service/PlatformSettingService.java` (drop `DISCOUNT_BASE`,
  `COMMISSION_ESTIMATE_PCT`, `REFERENCE_FARE_BASKET`, `REFERENCE_FARE_BASKET_HOTEL`: constants, `DEFAULTS` entries,
  `discountBase()`, `referenceFareBasket()`, their `validate` cases, `parseBasket`),
  `service/PlatformSupplierCredentialService.java` (delete `commissionEstimatePct` only),
  `service/booking/PlatformBookingService.java` (`stamp`, `cancelLocked`, `ticket`)
- Delete: `enums/DiscountBase.java`, `models/ReferenceFare.java`, `dto/PricingRuleImpactResponse.java`,
  `dto/PricingPreviewResponse.java`
- Test: `src/test/java/com/voyra/crm/service/booking/AgencyPriceQuoterTest.java`, `util/PricingMathTest.java`,
  `service/AgencyPricingServiceTest.java`, `controller/PlatformAgencyPricingControllerTest.java`,
  `service/PlatformSettingServiceTest.java`, `service/PlatformSettingServiceCoverageTest.java`,
  `service/booking/PlatformBookingServiceTest.java`, `service/booking/PlatformBookingCancelTest.java` (all under
  `src/test/java/com/voyra/crm/`)

**Interfaces:**
- Produces: `record PriceQuote(BigDecimal displayed, BigDecimal agencyEarns, BigDecimal agencyCommission, BigDecimal tds,
  String pricingId, BigDecimal discountPct, BigDecimal commissionPct)`;
  `PricingMath.displayed(BigDecimal gross, BigDecimal discountPct) -> BigDecimal` (gross less discount % of gross,
  scale 2 HALF_UP); `AgencyPriceQuoter(AgencyPricingService, PlatformSettingService)` with `quote` and `quoterFor`
  unchanged in signature. Pricing rule JSON: `{id, kind, product, name, pct, isDefault, version, agencyCount, updatedAt,
  updatedByName}`; assignment JSON: `{product, discountRule, commissionRule, assigned, updatedAt, updatedByName}`.

- [ ] **Step 1: Write the failing tests**

```java
// AgencyPriceQuoterTest
@Test void commissionIsTheRulePercentageOfBaseWithNoCap() {
    // rules: discount 5 %, commission 8 %; tds_rate 2; gross 12000, base 10000
    PriceQuote q = quoter.quote(TENANT, PricingProduct.FLIGHT, new BigDecimal("12000"), new BigDecimal("10000"));
    assertThat(q.displayed()).isEqualByComparingTo("11400.00");
    assertThat(q.agencyCommission()).isEqualByComparingTo("800.00");
    assertThat(q.tds()).isEqualByComparingTo("16.00");
    assertThat(q.agencyEarns()).isEqualByComparingTo("784.00");
}
// PricingMathTest
@Test void displayedTakesTheDiscountOffGross() {
    assertThat(PricingMath.displayed(new BigDecimal("5000"), new BigDecimal("10"))).isEqualByComparingTo("4500.00");
    assertThat(PricingMath.displayed(new BigDecimal("999.99"), new BigDecimal("3.333"))).isEqualByComparingTo("966.66");
}
// AgencyPricingServiceTest
@Test void onlyAPctChangeBumpsTheVersion()            // rename -> version unchanged; pct 2 -> 3 -> version + 1
@Test void assignAuditsTheRulePairWithoutLossFigures() // PRICING_ASSIGN detail has no key "worstCaseNetPer10k", "lossMaking", "allowCommissionAboveEarned"
// PlatformSettingServiceTest
@ParameterizedTest @ValueSource(strings = {"discount_base", "commission_estimate_pct", "reference_fare_basket", "reference_fare_basket_hotel"})
void retiredKeysAreUnknown(String key) {
    assertThatThrownBy(() -> service.set(key, "1")).isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown setting: " + key);
}
// PlatformBookingServiceTest
@Test void reviewSnapshotCarriesNoVoyraFigures() // snapshot has no "voyraEarned", "voyraNet"; booking.getVoyraNet() and getVoyraEarned() are null
@Test void aFareVarianceAlertsButWritesNoVoyraNet() // VARIANCE event recorded, alert sent, getVoyraNet() null, no "Loss-making" alert
// PlatformBookingCancelTest
@Test void cancelPostsRefundAndRecallAndTouchesNoVoyraFigure() // REFUND_CREDIT and COMMISSION_RECALL posted as before; getVoyraNet(), getVoyraEarnedReversal() null; no "Loss-making" alert
```

Delete the impact and preview tests in `AgencyPricingServiceTest` and `PlatformAgencyPricingControllerTest`, and every
`allowAboveEarned` and `commissionEstimatePct` stub.

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='AgencyPriceQuoterTest,PricingMathTest,AgencyPricingServiceTest,PlatformSettingServiceTest,PlatformBookingServiceTest,PlatformBookingCancelTest'`
Expected: compilation failure on the new `PriceQuote` and `PricingMath.displayed` shapes.

- [ ] **Step 3: Implement**

`AgencyPriceQuoter.price(EffectivePricing rules, BigDecimal tdsPct, BigDecimal gross, BigDecimal base)`: commission =
base × rule pct / 100 rounded, tds = commission × tds % / 100 rounded, agencyEarns = commission - tds. In
`PlatformBookingService.stamp` stop setting `voyraEarned`, `voyraEarnedSource`, `voyraNet` and the snapshot keys
`voyraEarned`, `voyraNet`; in `cancelLocked` drop `netBefore`, `earned`, `setVoyraEarnedReversal`, both `setVoyraNet`
lines and the loss-making alert; in `ticket` drop `setVoyraNet` and the loss-making alert (the VARIANCE event and its
alert stay). `PricingRule` version bumps on a pct change only.

- [ ] **Step 4: Run the tests and the coverage check**

Run: the Step 2 command, then `scripts/coverage-gaps.sh 'AgencyPriceQuoterTest,PricingMathTest,AgencyPricingServiceTest,PlatformSettingService*Test' com.voyra.crm.service.AgencyPricingService`
Expected: PASS; no missed line or branch.

- [ ] **Step 5: Commit**

```bash
rtk git add -A src && rtk git commit -m "Price agency fares from the pricing rules alone

A commission rule now pays exactly its percentage of base, the discount always comes off gross, and the supplier
commission estimate, the reference fare baskets and the impact and preview figures are gone.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Qq6uXt8Qha7QvtoJUmYRa9"
```

### Task 2: P&L, statement import and Voyra's figures removed

**Files:**
- Delete: `service/PlatformPnlService.java`, `controller/PlatformPnlController.java`,
  `service/TripjackStatementService.java`, `service/TripjackStatementConfig.java`,
  `controller/PlatformStatementController.java`, `dto/PnlResponse.java`, `dto/PnlRow.java`,
  `dto/StatementImportResponse.java`, `dto/StatementLineResponse.java`, `entity/PlatformStatementImport.java`,
  `entity/PlatformStatementLine.java`, `repository/PlatformStatementImportRepository.java`,
  `repository/PlatformStatementLineRepository.java`, `enums/StatementLineStatus.java`, `enums/StatementLineType.java`,
  `enums/VoyraEarnedSource.java` and their tests (`PlatformPnlControllerTest`, `PlatformStatementControllerTest`,
  `PlatformPnlServiceTest`, `PlatformPnlServiceCoverageTest`, `TripjackStatementServiceTest`). The tables stay.
  `CsvStatementParser` stays (bank import uses it).
- Modify: `entity/PlatformBooking.java` (unmap `voyraEarned`, `voyraEarnedSource`, `voyraEarnedActual`,
  `voyraEarnedReversal`, `voyraNet`), `dto/PlatformAdminBookingResponse.java` (drop `voyraEarned`,
  `voyraEarnedSource`, `voyraEarnedActual`, `voyraNet`), `service/booking/PlatformBookingQueryService.java`
  (`adminViews`), `service/PlatformDashboardService.java`, `dto/PlatformDashboardResponse.java` (drop `voyraNet30d`),
  `repository/PlatformBookingRepository.java`, `security/ControllerSkuTable.java` (two entries and their imports)
- Test: `service/PlatformDashboardServiceTest.java`, `controller/PlatformDashboardControllerTest.java`,
  `controller/PlatformBookingControllerTest.java`, `service/booking/PlatformBookingQueryServiceTest.java`,
  `integration/PlatformBookingSummaryQueriesIT.java`, `security/ControllerSkuTableContractTest.java` (unchanged; 76
  controllers stay above its minimum of 71)

**Interfaces:**
- Produces: `PlatformBookingRepository.sumDisplayedTicketed(Instant from, Instant to, SupplierEnvironment environment,
  Collection<PlatformBookingStatus> statuses) -> BigDecimal` (JPQL `coalesce(sum(b.displayed), 0)` over
  `ticketedAt >= from and ticketedAt < to and environment = :environment and status in :statuses`). Dashboard JSON:
  `{from, to, gmv30d, walletFloat, pendingKyc, pendingTopups, unknownBookings, cancellingBookings,
  pastDueSubscriptions, failedEmails}`.

- [ ] **Step 1: Write the failing tests**

```java
// PlatformDashboardServiceTest
@Test void gmvIsTheDisplayedSumOfLiveBookingsTicketedInTheLast30IndianDays() {
    // today in IST 2026-11-01 -> from 2026-10-03T00:00+05:30, to 2026-11-02T00:00+05:30
    verify(bookingRepository).sumDisplayedTicketed(FROM, TO, SupplierEnvironment.PROD,
            Set.of(PlatformBookingStatus.TICKETED, PlatformBookingStatus.CANCELLING, PlatformBookingStatus.CANCELLED));
}
// PlatformDashboardControllerTest
@Test void dashboardHasNoVoyraNet() { mvc.perform(get("/api/platform/dashboard")).andExpect(jsonPath("$.voyraNet30d").doesNotExist()); }
// PlatformBookingControllerTest (admin list and detail)
@Test void adminBookingCarriesNoVoyraFigures() // jsonPath $.voyraEarned, $.voyraEarnedSource, $.voyraEarnedActual, $.voyraNet doNotExist; $.agencyEarns exists
// PlatformBookingSummaryQueriesIT
@Test void sumDisplayedTicketedCountsLiveTicketedCancellingAndCancelledRowsInRange() // a MOCK row, a FAILED row and a row ticketed a day after `to` are left out
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='PlatformDashboardServiceTest,PlatformDashboardControllerTest,PlatformBookingControllerTest,PlatformBookingQueryServiceTest'`
Expected: FAIL (`sumDisplayedTicketed` missing, `voyraNet30d` present).

- [ ] **Step 3: Implement** the deletions, the repository method and `dashboard()` reading it (the `COUNTED` set moves
  into `PlatformDashboardService`).

- [ ] **Step 4: Run the tests, the IT and the coverage check**

Run: the Step 2 command; `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock DOCKER_API_VERSION=1.41 TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock rtk ./mvnw -q test -Dapi.version=1.41 -Dtest=PlatformBookingSummaryQueriesIT`;
`scripts/coverage-gaps.sh 'PlatformDashboard*Test,PlatformBookingQueryServiceTest' com.voyra.crm.service.PlatformDashboardService`
Expected: PASS; no gap.

- [ ] **Step 5: Commit** ("Remove the P&L, the statement import and Voyra's own figures", trailers as in Global
  Constraints).

### Task 3: Supplier rows: V37 and the Suppliers API

**Files:**
- Create: `src/main/resources/db/migration/public/V37__suppliers_in_database.sql`, `dto/SupplierProductSettings.java`,
  `src/test/java/com/voyra/crm/integration/PlatformSuppliersMigrationIT.java`
- Delete: `dto/SupplierProductTerms.java`
- Modify: `enums/SupplierProvider.java` (`TRIPJACK, MOCK`), `enums/SupplierEnvironment.java` (doc: a supplier row's
  environment, fixed at create), `entity/PlatformSupplierProduct.java` (unmap `commissionEstimatePct`; add
  `searchEnabled`, `bookingEnabled`), `entity/PlatformSupplierCredential.java` (doc: several rows per integration;
  `isActive` stays mapped until Task 7), `repository/PlatformSupplierCredentialRepository.java` (add
  `existsByNameIgnoreCase(String)`, `existsByNameIgnoreCaseAndIdNot(String, String)`; delete `existsByProvider`),
  `dto/PlatformSupplierCreateRequest.java`, `dto/PlatformSupplierUpdateRequest.java`,
  `dto/PlatformSupplierCredentialResponse.java`, `service/PlatformSupplierCredentialService.java` (`list`, `get`,
  `create`, `update`, `toResponse`; delete `updateProducts`, `requireNoBookingsInFlight`, `DEFAULT_COMMISSION_PCT`,
  `validTerms`, `saveTerms`, `termsDetail`, `prodBaseUrl`, `ALLOWED_HOSTS`),
  `controller/PlatformSupplierCredentialController.java` (delete `PUT /{id}/products`; operation texts)
- Test: `service/PlatformSupplierCredentialServiceTest.java`, `controller/PlatformSupplierCredentialControllerTest.java`

**Interfaces:**
- Produces:
  - `SupplierProductSettings { PricingProduct product (@NotNull); boolean searchEnabled; boolean bookingEnabled }`
  - `PlatformSupplierCreateRequest { name (@NotBlank, @Size max 80); provider (@NotNull SupplierProvider); baseUrl
    (@NotBlank, max 255); apiKey (@NotBlank, max 500); products (@NotEmpty @Valid List<SupplierProductSettings>) }`
  - `PlatformSupplierUpdateRequest { name; provider (optional; must equal the stored one); baseUrl; apiKey (blank keeps);
    products (@NotEmpty, replaces the set) }`
  - Response JSON: `{id, name, provider, environment, baseUrl, maskedApiKey, products: [{product, searchEnabled,
    bookingEnabled}] (sorted FLIGHT, HOTEL), updatedAt, updatedBy}`
  - `PlatformSupplierCredentialService.normalizeBaseUrl(SupplierProvider provider, String baseUrl) -> NormalizedUrl`
    with `record NormalizedUrl(String baseUrl, SupplierEnvironment environment)` (package-private, static)
  - `POST /api/platform/supplier-credentials` 201, `PUT /{id}` 200, `GET`, `GET /{id}`, `POST /{id}/test` unchanged paths

- [ ] **Step 1: Write the migration IT**

`PlatformSuppliersMigrationIT` (plain JUnit, the `postgres:16-alpine` container of `AbstractIntegrationTest`, no
Spring context): each test migrates a fresh schema with `Flyway.configure().dataSource(...).schemas(name)
.locations("classpath:db/migration/public").target("36")`, inserts its rows with JDBC, then migrates to `"37"`.

```java
@Test void addsTheTogglesOffAndTheMockSupplierAndBackfillsMockBookings() {
    // before: Tripjack row (is_active, PROD) with FLIGHT and HOTEL terms; one MOCK platform_booking with credential_id null
    // after:
    assertThat(query("select count(*) from platform_supplier_product where search_enabled or booking_enabled")).isEqualTo(0);
    assertThat(query("select provider||'|'||environment||'|'||base_url||'|'||api_key_encrypted from platform_supplier_credential where name = 'Mock supplier'"))
            .isEqualTo("MOCK|MOCK|mock://local|");
    assertThat(query("select count(*) from platform_supplier_product p join platform_supplier_credential c on c.id = p.credential_id where c.provider = 'MOCK'")).isEqualTo(2);
    assertThat(query("select c.name from platform_booking b join platform_supplier_credential c on c.id = b.credential_id")).isEqualTo("Mock supplier");
    // a second Tripjack row now inserts; a name differing only in case does not
}
@Test void refusesWhileACappedCommissionRuleExceedsItsEstimate() {
    // FLIGHT estimate 5 on the active Tripjack row; COMMISSION rule pct 6, allow_above_earned false
    assertThatThrownBy(() -> migrateTo("37")).hasStackTraceContaining("V37: a commission rule");
}
@Test void refusesWhileDiscountBaseIsNotGross() {
    // platform_setting discount_base = 'BASE'
    assertThatThrownBy(() -> migrateTo("37")).hasStackTraceContaining("V37: discount_base is not GROSS");
}
```

- [ ] **Step 2: Write V37**

```sql
-- V37 (expand only, spec 2026-11-01 Part 1): suppliers in the database. V39, next release, drops what this stops reading.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform_setting WHERE key = 'discount_base' AND value <> 'GROSS') THEN
        RAISE EXCEPTION 'V37: discount_base is not GROSS; set it to GROSS and check agency prices, then deploy again';
    END IF;
    IF EXISTS (SELECT 1 FROM pricing_rule r
               WHERE r.kind = 'COMMISSION' AND NOT r.allow_above_earned
                 AND r.pct > COALESCE((SELECT p.commission_estimate_pct FROM platform_supplier_product p
                                       JOIN platform_supplier_credential c ON c.id = p.credential_id
                                       WHERE c.provider = 'TRIPJACK' AND c.is_active AND p.product = r.product), 5)) THEN
        RAISE EXCEPTION 'V37: a commission rule without allow-above-earned exceeds the supplier estimate; lower it or allow it, then deploy again';
    END IF;
END $$;

ALTER TABLE platform_supplier_credential DROP CONSTRAINT uq_platform_supplier_credential_provider;
DROP INDEX uq_platform_supplier_credential_active;
CREATE UNIQUE INDEX uq_platform_supplier_credential_name ON platform_supplier_credential (lower(name));

ALTER TABLE platform_supplier_product
    ADD COLUMN search_enabled  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN booking_enabled BOOLEAN NOT NULL DEFAULT false,
    ALTER COLUMN commission_estimate_pct SET DEFAULT 5; -- no longer written; old instances still read it

-- The mock is a supplier row. Its key is blank (SQL cannot encrypt with the app key); every toggle off.
INSERT INTO platform_supplier_credential (id, name, provider, environment, base_url, api_key_encrypted, is_active, created_at, updated_at)
SELECT gen_random_uuid()::text, 'Mock supplier', 'MOCK', 'MOCK', 'mock://local', '', false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM platform_supplier_credential WHERE lower(name) = 'mock supplier');
INSERT INTO platform_supplier_product (credential_id, product)
SELECT c.id, p.product FROM platform_supplier_credential c, (VALUES ('FLIGHT'), ('HOTEL')) p (product)
WHERE c.provider = 'MOCK' AND lower(c.name) = 'mock supplier'
ON CONFLICT DO NOTHING;
UPDATE platform_booking SET credential_id = (SELECT id FROM platform_supplier_credential WHERE provider = 'MOCK' AND lower(name) = 'mock supplier')
WHERE environment = 'MOCK' AND credential_id IS NULL;
```

- [ ] **Step 3: Write the failing service and controller tests**

```java
// PlatformSupplierCredentialServiceTest
@Test void createStoresTheNormalizedUrlAndTheEnvironmentItDecides() {
    // "https://API.Tripjack.com./" -> baseUrl "https://api.tripjack.com", PROD; "https://apitest.tripjack.com" -> UAT
}
@ParameterizedTest @ValueSource(strings = {"http://api.tripjack.com", "https://api.tripjack.com:8443", "https://u@api.tripjack.com",
        "https://api.tripjack.com/x", "https://api.tripjack.com?a=1", "https://evil.example.com"})
void createRefusesAUrlOffTheAllowList(String url) // IllegalArgumentException, nothing saved
@Test void theMockTakesMockLocalOnly()            // MOCK "mock://local" -> MOCK; MOCK "https://api.tripjack.com" -> 400
@Test void creatingAMockRowOnCloudrunIs400()      // springEnv.matchesProfiles("cloudrun") -> BookingRequestException code PROVIDER_UNAVAILABLE
@Test void aNameTakenIgnoringCaseIs409()          // existsByNameIgnoreCase("tripjack") -> BookingConflictException SUPPLIER_EXISTS "A supplier named tripjack already exists"
@Test void aProductListedTwiceIs400()             // "FLIGHT is listed twice"
@Test void updateRefusesAnotherProvider()         // "The integration of a supplier cannot change"
@Test void updateRefusesAUrlOfTheOtherEnvironment() // stored UAT, new https://api.tripjack.com -> "Add a new supplier for the production account"
@Test void updateReplacesTheProductSetAndKeepsABlankKey() // stored FLIGHT+HOTEL, request FLIGHT only -> HOTEL row deleted; key unchanged
@Test void onlyANameUrlOrKeyEditBumpsUpdatedAt()  // toggles only -> credential.updatedAt unchanged, product row updatedAt now
@Test void auditNamesTheChangedFieldsAndNeverTheKey() // CREDENTIAL_SET detail "changed" = ["apiKey","products"]; no detail value contains the plaintext key
@Test void aBlankStoredKeyMasksAsDots()           // V37's mock row: maskedApiKey "••••••"
// PlatformSupplierCredentialControllerTest
@Test void createReturns201WithTheProductToggles() // jsonPath $.products[0].searchEnabled, $.baseUrl, no $.isActive, no $.apiKey
@Test void productsEndpointIsGone()                // PUT /api/platform/supplier-credentials/{id}/products -> 404 or 405
```

- [ ] **Step 4: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='PlatformSupplierCredentialServiceTest,PlatformSupplierCredentialControllerTest'`
Expected: compilation failure (`SupplierProductSettings`, `normalizeBaseUrl` missing).

- [ ] **Step 5: Implement**

`normalizeBaseUrl`: MOCK accepts `mock://local` (trimmed, ignoring case) only; TRIPJACK parses with `URI.create`,
requires https, no user info, port -1, no query or fragment, path empty or `/`, lower-cases the host and strips one
trailing dot, then looks it up in `TRIPJACK_HOSTS = Map.of("apitest.tripjack.com", UAT, "api.tripjack.com", PROD)` and
returns `"https://" + host`. Refusal: "Base URL must be a Tripjack UAT or production host over https" / "The mock
supplier's base URL is mock://local". The service takes `org.springframework.core.env.Environment springEnv` for the
cloudrun check. A blank `api_key_encrypted` decrypts to `""` (one private helper used by `decrypt` and `toResponse`).
Product rows not in an update's list are deleted. Audit `CREDENTIAL_SET` detail: `credentialId, name, provider,
environment, changed (subset of name, baseUrl, apiKey, products, in that order), products ({FLIGHT: {search, booking}})`.

- [ ] **Step 6: Run the tests, the IT and the coverage check**

Run: the Step 4 command; the colima prefix of Task 2 with `-Dtest=PlatformSuppliersMigrationIT`;
`scripts/coverage-gaps.sh 'PlatformSupplierCredential*Test' com.voyra.crm.service.PlatformSupplierCredentialService`
Expected: PASS; no gap.

- [ ] **Step 7: Commit** ("Keep suppliers as rows with search and booking switched per product", trailers).

### Task 4: Flight search asks every enabled supplier

**Files:**
- Create: `service/booking/SupplierRegistry.java`, `util/SupplierIds.java`, `service/SupplierFanOut.java`,
  `exception/SuppliersUnavailableException.java`; tests `service/booking/SupplierRegistryTest.java`,
  `util/SupplierIdsTest.java`, `service/SupplierFanOutTest.java`
- Modify: `models/ResolvedCredential.java`, `service/FlightSearchProvider.java`, `service/MockFlightSearchProvider.java`,
  `service/tripjack/TripjackFlightSearchProvider.java` (drop `@ConditionalOnProperty`, the single-argument `search` and
  the credential-service dependency), `service/FlightSearchService.java`, `dto/FlightOfferResponse.java`,
  `dto/FlightSearchPageResponse.java`, `service/PlatformSupplierCredentialService.java` (add the row queries below;
  `decrypt` builds the new `ResolvedCredential`; delete `hasActive`), `repository/PlatformSupplierProductRepository.java`,
  `service/PlatformSupplierTestService.java`, `exception/GlobalExceptionHandler.java`, `src/main/resources/application.properties`
  (delete the `app.flight-supplier.provider` block)
- Delete: `config/SupplierStartupLogger.java` and its case in `src/test/java/com/voyra/crm/config/ConfigBeansTest.java`
- Test: also `service/FlightSearchServiceTest.java`, `service/MockFlightSearchProviderTest.java`,
  `service/tripjack/TripjackFlightSearchProviderTest.java` (+ `CoverageTest`), `service/PlatformSupplierTestServiceTest.java`,
  `exception/GlobalExceptionHandlerTest.java`, `controller/FlightSearchControllerTest.java`; every test that builds a
  `ResolvedCredential` (compile fix)

**Interfaces:**
- Produces:
  - `record ResolvedCredential(String id, String name, SupplierProvider provider, SupplierEnvironment environment,
    String baseUrl, String apiKey, String userId, Instant updatedAt)` (`toString` keeps masking key and user id)
  - `FlightSearchProvider`: `SupplierProvider provider()`; `List<FlightOfferResponse> search(FlightSearchQuery query,
    ResolvedCredential supplier)` (the mock ignores `supplier`)
  - `PlatformSupplierCredentialService`: `List<ResolvedCredential> searchEnabled(PricingProduct product)` (ordered by
    name), `Optional<ResolvedCredential> find(String id)`, `Set<String> bookingEnabledIds(PricingProduct product)`;
    `resolve(String id)` stays (404 when missing)
  - `PlatformSupplierProductRepository.findByProductAndSearchEnabledTrue(PricingProduct)`,
    `findByProductAndBookingEnabledTrue(PricingProduct)`
  - `SupplierRegistry(PlatformSupplierCredentialService suppliers, List<FlightSearchProvider> flightSearch)`:
    `List<ResolvedCredential> searchable(PricingProduct product)`, `FlightSearchProvider flightSearch(SupplierProvider)`
    (IllegalStateException when absent), `Optional<ResolvedCredential> find(String supplierId)`,
    `Set<String> bookingEnabledIds(PricingProduct)`; `@EventListener(ApplicationReadyEvent.class) void logSuppliers()`
  - `SupplierIds`: `record Scoped(String supplierId, String id)`; `static String wrap(String supplierId, String id)`
    (null id gives null); `static Optional<Scoped> parse(String scoped)`
  - `SupplierFanOut`: public no-arg constructor (8 threads, queue 64, 40 s) and package-private
    `SupplierFanOut(ExecutorService executor, Duration deadline)`; `record Result<T>(List<T> answers, List<String> failed)`;
    `<T> Result<T> run(List<ResolvedCredential> suppliers, Function<ResolvedCredential, List<T>> call)`; `@PreDestroy shutdown()`
  - `SuppliersUnavailableException` -> 503, body `code: "SUPPLIERS_UNAVAILABLE"`, message "Suppliers are not
    responding; try again in a minute"
  - Offer JSON adds `supplierId`, `supplierName`; `offerId`, `specialReturnId` and each `pairsWith` entry are
    `<supplierId>:<provider id>`. Page JSON adds `suppliersFailed: string[]` (names).

- [ ] **Step 1: Write the failing tests**

```java
// SupplierIdsTest
@Test void parsesAtTheFirstColonOnly() { assertThat(SupplierIds.parse("s1:opt:9:x")).contains(new Scoped("s1", "opt:9:x")); }
@Test void noNamespaceOrABlankSideIsEmpty() // parse(null), parse("abc"), parse(":x"), parse("s1:") all empty
@Test void wrapOfNullIsNull()
// SupplierFanOutTest
@Test void collectsEveryAnswerInSupplierOrder()
@Test void aThrowingSupplierIsFailedByName()       // failed == ["Tripjack"], answers from the other
@Test void aSupplierStillRunningAtTheDeadlineIsFailed() // deadline 50 ms, the slow call sleeps 500 ms
@Test void aRejectedTaskIsFailed()                 // executor 1 thread, SynchronousQueue, first task blocks
@Test void theTaskSeesTheCallersMdc()              // MDC "requestId" visible inside the call
// SupplierRegistryTest
@Test void searchableSkipsARowWhoseIntegrationIsNotInTheBuildAndWarnsOnce() // only the MOCK bean; a TRIPJACK row is skipped; LogCapture shows one WARN over two calls
// FlightSearchServiceTest
@Test void offersFromEverySupplierCarryItsIdAndNameAndNamespacedIds() {
    // s1 "Mock supplier", s2 "Mock supplier 2", both MOCK, both answer offerId "mock-6E-1", pairsWith ["r1"]
    assertThat(offers).extracting(FlightOfferResponse::getOfferId).containsExactly("s1:mock-6E-1", "s2:mock-6E-1");
    assertThat(offers).extracting(FlightOfferResponse::getSupplierName, FlightOfferResponse::getSupplier)
            .containsExactly(tuple("Mock supplier", "Mock supplier"), tuple("Mock supplier 2", "Mock supplier 2"));
    assertThat(offers.get(1).getPairsWith()).containsExactly("s2:r1");
}
@Test void noSearchableSupplierIs409() // BookingConflictException code NO_SUPPLIER, "No supplier is enabled for flight search"
@Test void everySupplierFailingIs503() // SuppliersUnavailableException
@Test void aPartialPageNamesTheFailedSupplierAndIsNotCached() // suppliersFailed ["Tripjack"]; the same request again calls the fan-out again
@Test void aToggleOrEditOfASupplierMissesTheCache() // cacheKey differs when the searchable set or a supplier's updatedAt differs
// PlatformSupplierTestServiceTest
@Test void testsTheRowThroughItsOwnIntegration() // MOCK row -> mock bean, ok, "Mock supplier answered with 8 offers"
// GlobalExceptionHandlerTest
@Test void suppliersUnavailableIs503WithItsCode()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='SupplierIdsTest,SupplierFanOutTest,SupplierRegistryTest,FlightSearchServiceTest,PlatformSupplierTestServiceTest,GlobalExceptionHandlerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`SupplierFanOut.run`: one `CompletableFuture.supplyAsync` per supplier on the executor, the task getting the row and a
copy of the caller's MDC; `allOf(...).orTimeout(deadline)` joined while swallowing the timeout; a future not completed
normally (rejected, threw, still running) adds the supplier's name to `failed` and logs `"Supplier {} search failed:
{}"` with the error class only. `FlightSearchService(SupplierRegistry, SupplierFanOut, AgencyPriceQuoter)`: `offers(query)`
takes `registry.searchable(FLIGHT)` (empty: 409), runs the fan-out with a call that searches and then stamps each offer
(`supplierId`, `supplierName`, `supplier` = name, wrapped ids), and throws 503 when every supplier failed. `searchPage`
caches only a result with no failure under
`cacheKey(String tenantId, FlightSearchQuery q, List<ResolvedCredential> suppliers)`, which appends the sorted
`id + "@" + updatedAt` of the searchable rows. `searchable` WARNs once per row id ("Supplier {name} ({provider}) is on
for {product} search but this build has no {provider} integration; skipped"); `logSuppliers` INFO-logs each enabled row
(name, provider, environment, products), never a key. The test service calls `registry.flightSearch(c.provider())
.search(query(), c)` and reports `c.name() + " answered with " + n + " offers"`. `PlatformBookingService` is not touched
until Task 7, so between Tasks 4 and 7 a running app's Book with Voyra refuses the namespaced ids: run no e2e in
between (unit tests are unaffected).

- [ ] **Step 4: Run the tests and the coverage check**

Run: the Step 2 command plus `MockFlightSearchProvider*Test,TripjackFlightSearchProvider*Test,FlightSearchControllerTest,ConfigBeansTest`;
`scripts/coverage-gaps.sh 'Supplier*Test,FlightSearchServiceTest' com.voyra.crm.service`
Expected: PASS; no gap in the new and changed classes.

- [ ] **Step 5: Commit** ("Search every flight supplier that has search switched on", trailers).

### Task 5: Hotel search asks every enabled supplier

**Files:**
- Modify: `service/HotelSearchProvider.java`, `service/MockHotelSearchProvider.java`,
  `service/tripjack/TripjackHotelSearchProvider.java` (drop `@ConditionalOnProperty`; `connect(ResolvedCredential)`;
  `refreshStaticData(ResolvedCredential)`), `service/HotelSearchService.java`, `controller/HotelSearchController.java`
  (thumbnails through the service; no provider field), `scheduler/TripjackHotelStaticDataJob.java` (no conditional),
  `dto/HotelOfferResponse.java`, `dto/HotelSearchPageResponse.java`, `dto/HotelDetailResponse.java`,
  `service/booking/SupplierRegistry.java`, `src/main/resources/application.properties` (delete
  `app.hotel-supplier.provider`; keep the two hms host properties)
- Test: `service/HotelSearchServiceTest.java`, `controller/HotelSearchControllerTest.java`,
  `service/MockHotelSearchProviderTest.java`, `service/tripjack/TripjackHotel*Test.java`,
  `scheduler/TripjackHotelStaticDataJobTest.java` (create), `service/booking/SupplierRegistryTest.java`

**Interfaces:**
- Consumes: Task 4's `SupplierRegistry`, `SupplierIds`, `SupplierFanOut`, `ResolvedCredential`.
- Produces:
  - `HotelSearchProvider`: `SupplierProvider provider()`; `search(HotelSearchQuery, ResolvedCredential)`;
    `detail(String hotelId, HotelSearchQuery, ResolvedCredential)`; `thumbnails(List<String> hotelIds, ResolvedCredential)`
  - `SupplierRegistry` constructor gains `List<HotelSearchProvider> hotelSearch`; `HotelSearchProvider hotelSearch(SupplierProvider)`
  - `HotelSearchService.thumbnails(List<String> hotelIds) -> List<HotelThumbnailResponse>`
  - Offer JSON adds `supplierId`, `supplierName`; `offerId`, `hotelId`, `correlationId` namespaced. Page JSON adds
    `suppliersFailed`. Detail JSON adds `supplierId`, `supplierName`, `bookingEnabled`; `hotelId` and each option's
    `optionId` namespaced. `POST /api/hotels/{hotelId}/detail` takes the namespaced id; its query's `correlationId` is
    the namespaced one from the listing.

- [ ] **Step 1: Write the failing tests**

```java
// HotelSearchServiceTest
@Test void hotelsFromTwoSuppliersAreSeparateRowsCheapestFirst() // s1 and s2 both answer hotel "h1": offers "s1:h1" and "s2:h1", ordered by displayedFare, supplierName on each
@Test void noSearchableHotelSupplierIs409()   // "No supplier is enabled for hotel search"
@Test void aPartialSearchIsKeptForPagingWithItsFailedSupplier() // page 2 with the searchId slices the same list and still reports suppliersFailed ["Tripjack"]
@Test void detailGoesToTheHotelsOwnSupplier() {
    // detail("s2:h1", query with correlationId "s2:c9")
    verify(mockProvider).detail(eq("h1"), argThat(q -> "c9".equals(q.getCorrelationId())), eq(S2));
    assertThat(d.getHotelId()).isEqualTo("s2:h1");
    assertThat(d.getOptions()).extracting(HotelDetailResponse.RoomOption::getOptionId).containsExactly("s2:o1");
    assertThat(d.getSupplierName()).isEqualTo("Mock supplier 2");
}
@Test void detailWithACorrelationIdOfAnotherSupplierIs400() // ("s1:h1", "s2:c9") -> BookingRequestException OFFER_UNAVAILABLE "This hotel is no longer available; search again"
@Test void thumbnailsGoToEachHotelsSupplierAndKeepTheirIds() // ["s1:h1","s2:h7","junk"] -> one call per supplier, ids re-namespaced, "junk" ignored
// TripjackHotelStaticDataJobTest
@Test void refreshesEachTripjackHotelSupplierAndSkipsTheMock()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='HotelSearchServiceTest,HotelSearchControllerTest,TripjackHotelStaticDataJobTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

Search: the Task 4 fan-out over `registry.searchable(HOTEL)`, stamping each offer, then pricing, then sorting by
`displayedFare` (falling back to `price`); the kept `Result` adds `List<String> failed`. Detail: parse the hotel id (no
namespace or unknown supplier: 400), require the correlation id (when present) on the same supplier, call the provider
with the unwrapped ids, then namespace and price; `bookingEnabled` from `registry.bookingEnabledIds(HOTEL)`. Thumbnails:
group the parsable ids by supplier, one provider call per known supplier, a supplier that throws contributes nothing.
The job calls `refreshStaticData(s)` for each `registry.searchable(HOTEL)` row whose provider is TRIPJACK, each in its
own try/catch with the existing log lines.

- [ ] **Step 4: Run the tests and the coverage check**

Run: the Step 2 command plus `MockHotelSearchProviderTest,TripjackHotel*Test,SupplierRegistryTest`;
`scripts/coverage-gaps.sh 'HotelSearch*Test,TripjackHotel*Test' com.voyra.crm.service.HotelSearchService`
Expected: PASS; no gap.

- [ ] **Step 5: Commit** ("Search every hotel supplier that has search switched on", trailers).

### Task 6: One row per flight across suppliers

**Files:**
- Create: `dto/SearchSupplierResponse.java`
- Modify: `service/FlightResultPaging.java`, `dto/FlightGroupResponse.java`, `dto/FlightSearchPageRequest.java`,
  `dto/FlightSearchPageResponse.java`, `service/FlightSearchService.java` (pass `registry.bookingEnabledIds(FLIGHT)` to `page`)
- Test: `service/FlightResultPagingTest.java`, `service/FlightSearchServiceTest.java`

**Interfaces:**
- Consumes: offers stamped with `supplierId`, `supplierName` (Task 4).
- Produces:
  - `SearchSupplierResponse { String supplierId; String supplierName; boolean bookingEnabled }`
  - `FlightGroupResponse` adds `String cheapestSupplierName`, `int supplierCount`; `cheapestPrice` is now the leading
    fare's `displayedFare` (falling back to `totalPrice`, then `price`)
  - `FlightSearchPageRequest` adds `List<String> suppliers` (supplier ids)
  - `FlightSearchPageResponse` adds `List<SearchSupplierResponse> suppliers` (every supplier in the result, by name)
  - `FlightResultPaging`: `static String groupKey(FlightOfferResponse o)`; `static List<FlightGroupResponse> group(List<FlightOfferResponse>)`;
    `static FlightGroupResponse summarise(FlightGroupResponse g, List<FlightOfferResponse> fares, Set<String> bookingSupplierIds)`
    (always a copy); `static FlightSearchPageResponse page(List<FlightGroupResponse> all, FlightSearchPageRequest req, Set<String> bookingSupplierIds)`

- [ ] **Step 1: Write the failing tests**

```java
@Test void twoProviderShapesOfOneFlightGroupIntoOneRow() {
    // mock: airlineCode "6E", flightNumber "6E-206", 06:15 -> 09:20 on 2026-12-01
    // tripjack: airlineCode "6e", flightNumber "206", 06:15:00 -> 09:20:00 on 2026-12-01
    // a third: flightNumber "6E 0206"
    assertThat(FlightResultPaging.group(List.of(mock, tripjack, third))).hasSize(1).first()
            .extracting(g -> g.getFares().size()).isEqualTo(3);
}
@Test void aDifferentDayOrDirectionIsAnotherRow()
@Test void theRowLeadsWithTheCheapestFareOfASupplierTakingBookings() {
    // s1 displayed 5000 (booking off), s2 displayed 5200 (booking on)
    FlightGroupResponse g = FlightResultPaging.summarise(group, group.getFares(), Set.of("s2"));
    assertThat(g.getCheapestSupplierName()).isEqualTo("Mock supplier 2");
    assertThat(g.getCheapestPrice()).isEqualByComparingTo("5200");
    assertThat(g.getSupplierCount()).isEqualTo(2);
}
@Test void everyFilteredCopyRecomputesTheSummary() // refundableOnly keeps only s2's fares -> supplierCount 1, cheapestSupplierName "Mock supplier 2"
@Test void theSuppliersFilterKeepsOnlyThoseSuppliers() // suppliers ["s2"] -> no s1 fare on any row; a row with only s1 fares is gone
@Test void thePageIsComboOnlyWhenEverySupplierAnsweredCombo() // s1 COMBO + s2 ONWARD/RETURN -> ROUND_TRIP_SPLIT, the COMBO row stays in flights
@Test void thePageListsTheSuppliersInTheResultWithTheirBookingState() // [{s1, "Mock supplier", false}, {s2, "Mock supplier 2", true}]
@Test void aCachedGroupIsNeverMutated() // page() twice with different filters leaves group.getFares() and cheapestPrice as built
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='FlightResultPagingTest,FlightSearchServiceTest'`
Expected: FAIL / compilation failure.

- [ ] **Step 3: Implement**

`groupKey`, one segment per leg (`o.getLegs()` when not empty, else the offer itself), legs joined with `/`, then
`|` + direction:
`code = upper(trim(airlineCode)) or, when blank, upper(trim(airline))`;
`number = upper(trim(flightNumber))`, a leading `code` and then one `-` or space removed, non-digits removed, leading
zeros removed (all zeros stays `0`);
`depart = departDate + "T" + departTime truncated to minutes`, `arrive` the same (a null part is empty);
segment `code|number|depart|arrive`.
`summarise` sorts the fares: taking bookings (`f.isBookable() && bookingSupplierIds.contains(f.getSupplierId())`) first,
then by `displayedFare ?? totalPrice ?? price`, then `supplierName`; it sets `cheapestPrice`, `cheapestSupplierName`
(the first fare's) and `supplierCount` (distinct supplier ids). `group` no longer sorts or summarises; `page` applies
the stops, airlines, refundable and suppliers filters to each group's fares, summarises every group (main and returns),
then sorts by `cheapestPrice` as today. Trip type: no return date ONE_WAY; else ROUND_TRIP_COMBO when the main list is
not empty, every main group is COMBO and there are no return groups; else ROUND_TRIP_SPLIT.

- [ ] **Step 4: Run the tests and the coverage check**

Run: the Step 2 command; `scripts/coverage-gaps.sh 'FlightResultPagingTest,FlightSearchServiceTest' com.voyra.crm.service.FlightResultPaging`
Expected: PASS; no gap.

- [ ] **Step 5: Commit** ("Show one row per flight across suppliers, led by the cheapest one taking bookings", trailers).

### Task 7: Bookings go to the chosen supplier

**Files:**
- Modify: `service/booking/BookingSupplier.java`, `service/booking/FlightBookingProvider.java`,
  `service/booking/HotelBookingProvider.java`, `service/booking/MockFlightBookingProvider.java`,
  `service/booking/MockHotelBookingProvider.java`, `service/booking/TripjackFlightBookingProvider.java`,
  `service/booking/TripjackHotelBookingProvider.java` (all four: no `@ConditionalOnProperty`; the Tripjack two drop the
  credential-service dependency and call with the given row), `service/booking/FlightBookingProviderConfig.java`,
  `service/booking/HotelBookingProviderConfig.java` (delete `provider`), `service/booking/SupplierRegistry.java`,
  `service/booking/PlatformBookingService.java`, `service/booking/PlatformBookingPoller.java`,
  `service/PlatformSupplierCredentialService.java` (delete `resolveActive`), `entity/PlatformSupplierCredential.java`
  (unmap `isActive`), `repository/PlatformSupplierCredentialRepository.java` (delete `findByProviderAndIsActiveTrue`),
  `service/PlatformSettingService.java` (delete `TRIPJACK_LIVE_READY`, its default, `tripjackLiveReady()` and its
  `validate` case), `src/main/resources/application.properties` (delete `app.flight-booking.provider`,
  `app.hotel-booking.provider` and their comment lines; the `mock.*` and `poll.*` lines stay)
- Delete: `service/booking/BookingProviderRegistry.java`, `service/booking/TripjackLiveSwitchGuard.java`,
  `src/test/java/com/voyra/crm/service/booking/TripjackLiveSwitchGuardTest.java`
- Test: `service/booking/SupplierRegistryTest.java`, `service/booking/PlatformBookingServiceTest.java`,
  `service/booking/PlatformBookingCancelTest.java`, `service/booking/PlatformBookingPollerTest.java`,
  `service/booking/Mock*BookingProvider*Test.java`, `service/booking/Tripjack*BookingProviderTest.java`,
  `service/PlatformSettingServiceTest.java`

**Interfaces:**
- Consumes: Task 4's `SupplierRegistry`, `SupplierIds`, `ResolvedCredential`.
- Produces:
  - `BookingSupplier`: `SupplierProvider provider()`; `boolean cancelIsAsync()` (Tripjack true, mock false);
    `BookResult book(BookRequest, ResolvedCredential)`; `BookResult bookingDetails(String, ResolvedCredential)`;
    `CancelQuote cancelQuote(String, ResolvedCredential)`; `CancelResult cancel(String, ResolvedCredential)`
  - `FlightBookingProvider`: `FareReview review(List<String> offerIds, int adults, int children, int infants,
    ResolvedCredential supplier)`; `default FareReview review(FlightReviewRequest r, ResolvedCredential supplier)`
  - `HotelBookingProvider`: `FareReview review(String optionId, String hotelId, LocalDate checkIn, LocalDate checkOut,
    List<HotelRoomRequest> rooms, ResolvedCredential supplier)`
  - `SupplierRegistry` constructor adds `List<FlightBookingProvider>`, `List<HotelBookingProvider>`,
    `FlightBookingProviderConfig`, `HotelBookingProviderConfig`; methods `FlightBookingProvider flightBooking(SupplierProvider)`,
    `HotelBookingProvider hotelBooking(SupplierProvider)`, `boolean bookable(String supplierId, PricingProduct product)`
    (booking on for that product and a booking bean exists), `boolean serves(PlatformBooking b)` (credential id set,
    row found, booking bean for its provider and product, row environment equals `b.getEnvironment()`),
    `BoundSupplier supplierFor(PlatformBooking b)` (IllegalStateException when not served), `int pollMaxMinutes(PricingProduct)`
  - `record BoundSupplier(BookingSupplier provider, ResolvedCredential credential)` with `book(BookRequest)`,
    `bookingDetails(String)`, `cancelQuote(String)`, `cancel(String)`, `cancelIsAsync()` delegating
  - Review errors: 400 `OFFER_UNAVAILABLE`, 400 `MIXED_SUPPLIERS`, 409 `SUPPLIER_NOT_BOOKABLE` (copy in Global
    Constraints); 409 `LIVE_BOOKINGS_PAUSED` is gone

- [ ] **Step 1: Write the failing tests**

```java
// SupplierRegistryTest
@Test void servesNeedsTheRowItsIntegrationAndAMatchingEnvironment() // null credentialId, missing row, env UAT vs row PROD -> false; matching -> true
@Test void bookableNeedsBookingOnForThatProduct()                  // FLIGHT on, HOTEL off
@Test void supplierForBindsTheRowsProviderAndRow()
// PlatformBookingServiceTest
@Test void reviewStampsTheChosenSuppliersIdAndEnvironment() {
    // request offerId "s2:mock-6E-1", s2 = Mock supplier 2 (MOCK)
    verify(mockFlightBooking).review(argThat(r -> "mock-6E-1".equals(r.getOfferId())), eq(S2));
    assertThat(saved.getCredentialId()).isEqualTo("s2");
    assertThat(saved.getEnvironment()).isEqualTo(SupplierEnvironment.MOCK);
}
@ParameterizedTest @ValueSource(strings = {"mock-6E-1", "unknown:mock-6E-1"})
void reviewOfAnUnnamespacedOrUnknownOfferIs400(String offerId) // BookingRequestException OFFER_UNAVAILABLE "This fare is no longer available; search again"; no row saved
@Test void reviewWithLegsOnTwoSuppliersIs400()      // "s1:a" + return "s2:b" -> MIXED_SUPPLIERS "Both legs must be on one supplier"
@Test void reviewOnASupplierNotTakingBookingsIs409() // code SUPPLIER_NOT_BOOKABLE, "Tripjack is not taking bookings right now; pick another supplier"; provider not called
@Test void hotelReviewWithHotelAndRoomOnTwoSuppliersIs400() // "The hotel and the room must be on one supplier"
@Test void confirmRefusesWhenBookingWasSwitchedOffSinceTheReview() // 409 SUPPLIER_NOT_BOOKABLE, row stays REVIEWING, no hold
@Test void confirmOfAReviewOlderThanASupplierEditIsExpired() // supplier updatedAt after booking createdAt -> REVIEW_EXPIRED, no hold
@Test void confirmAfterAToggleOnlyEditStillHolds()  // updatedAt before createdAt -> HELD
// PlatformBookingCancelTest
@Test void anAsyncSupplierStaysCancellingUntilThePollerConfirms() // bound Tripjack: CANCEL_ACCEPTED recorded, status CANCELLING, no refund posted
@Test void aSyncSupplierSettlesAtOnce()             // bound mock: CANCELLED, refund and recall posted
@Test void cancelStillWorksAfterBookingIsSwitchedOff() // bookable false, serves true -> CANCELLED
@Test void aRowWhoseSupplierNoLongerMatchesIsNotServed() // 409 BOOKING_UNAVAILABLE, one SUPPLIER_MISMATCH event
// PlatformBookingPollerTest
@Test void pollsAnInFlightBookingThroughItsOwnSupplierAfterBookingIsSwitchedOff()
@Test void skipsAndAlertsOnceForARowWhoseSupplierIsMissing()
// Tripjack*BookingProviderTest
@Test void callsWithTheGivenRowsKeyAndHost() // MockRestServiceServer expects the "apikey" header of the passed row; hotel host follows its environment
// PlatformSettingServiceTest
@Test void tripjackLiveReadyIsAnUnknownSetting() // "Unknown setting: tripjack_live_ready"
```

Delete the `LIVE_BOOKINGS_PAUSED`, `isMock` and provider-switch tests.

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='SupplierRegistryTest,PlatformBooking*Test,*BookingProvider*Test,PlatformSettingServiceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`review`: parse `offerId` (and `returnOfferId` when present) with `SupplierIds.parse` (empty: 400), refuse two supplier
ids (400), set the provider ids back on the request, then `admit(principal, FLIGHT, supplierId, clientId, serviceId,
leadId) -> ResolvedCredential` (the existing refusals first, then unknown supplier 400, then not bookable 409), call
`registry.flightBooking(supplier.provider()).review(request, supplier)`, and `stampReview(..., supplier, ...)` stamps
`credentialId(supplier.id())` and `environment(supplier.environment())`. `reviewHotel` the same over `hotelId` and
`optionId`. `ReviewEnv` and `requireLiveAllowed` go. `hold`, in place of `requireLiveAllowed`: not bookable 409, then
the existing `serves` branch, then `supplier.updatedAt().isAfter(b.getCreatedAt())` throws `reviewExpired`. Every
supplier call goes through `registry.supplierFor(b)`; in `cancel` the `environment != MOCK` test becomes
`bound.cancelIsAsync()`; the poller's `environment == MOCK` test becomes `!registry.supplierFor(b).cancelIsAsync()`,
evaluated after its `serves` check. `alertNotServed` records `{environment, status, credentialId}` and says the row's
supplier is missing, not in this build, or in another environment.

- [ ] **Step 4: Run the tests and the coverage check**

Run: the Step 2 command; `scripts/coverage-gaps.sh 'SupplierRegistryTest,PlatformBooking*Test' com.voyra.crm.service.booking`
Expected: PASS; no gap.

- [ ] **Step 5: Commit** ("Send every booking call to the supplier the booking was reviewed on", trailers).

### Task 8: Booking responses name the supplier

**Files:**
- Modify: `dto/PlatformBookingResponse.java` (add `supplierName`; the admin response inherits it),
  `service/booking/PlatformBookingQueryService.java` (`agencyViews`, `adminViews`)
- Test: `service/booking/PlatformBookingQueryServiceTest.java`, `controller/PlatformBookingControllerTest.java`

**Interfaces:**
- Produces: agency and admin booking JSON add `supplierName` (string); no `credentialId` in agency JSON.

- [ ] **Step 1: Write the failing tests**

```java
@Test void agencyAndAdminViewsNameTheSupplierInOneLookup() // two rows on s1, one on s2 -> names; credentialRepository.findAllById called once
@Test void agencyBookingJsonNamesTheSupplierAndHidesItsId() // jsonPath $.supplierName "Mock supplier"; $.credentialId doesNotExist; the leak assertions stay
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk ./mvnw -q test -Dtest='PlatformBookingQueryServiceTest,PlatformBookingControllerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement** with the existing `names(rows, PlatformBooking::getCredentialId, credentialRepository::findAllById, PlatformSupplierCredential::getId, PlatformSupplierCredential::getName)` helper.

- [ ] **Step 4: Run the tests** (Step 2 command; `scripts/coverage-gaps.sh 'PlatformBookingQueryServiceTest' com.voyra.crm.service.booking.PlatformBookingQueryService`). Expected: PASS.

- [ ] **Step 5: Commit** ("Name the supplier on agency and admin bookings", trailers).

### Task 9: Backend gate

**Files:** none new; fixes only where the gate points.

- [ ] **Step 1: Check nothing still reads a removed switch**

Run: `rtk grep -rn "app.flight-supplier.provider\|app.hotel-supplier.provider\|app.flight-booking.provider\|app.hotel-booking.provider\|FLIGHT_SUPPLIER\|HOTEL_SUPPLIER\|BOOKING_PROVIDER\|tripjack_live_ready\|LIVE_BOOKINGS_PAUSED\|resolveActive\|commissionEstimatePct\|ConditionalOnProperty(name = \"app.\(flight\|hotel\)" src/main/java src/main/resources/application*.properties`
Expected: no match.

- [ ] **Step 2: Full build**

Run: `rtk ./mvnw verify`
Expected: BUILD SUCCESS: every test, JaCoCo 100 % lines, branches and methods, `scripts/blueprint-audit.sh` clean
(every controller still in `ControllerSkuTable`).

- [ ] **Step 3: Testcontainers ITs**

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock DOCKER_API_VERSION=1.41 TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock rtk ./mvnw -q test -Dapi.version=1.41 -Dtest='TenantIsolationIT,ProposalLinkResolutionIT,PlatformSuppliersMigrationIT,PlatformBookingSummaryQueriesIT,NotificationOutboxRepositoryIT,DocumentNumberServiceIT'`
Expected: PASS.

- [ ] **Step 4: Boot on the local database**

Build the jar, start it with `.claude/run/start-backend-mock.sh` (its `FLIGHT_SUPPLIER`/`HOTEL_SUPPLIER` exports are
ignored now; Task 15 removes them), check that the log shows Flyway applying V37 and the `SupplierRegistry` startup
lines, and that `GET /actuator/health` is UP. Every toggle is off after V37, so searches answer 409 `NO_SUPPLIER` until
Task 15's seed script runs.

- [ ] **Step 5: Commit** any gate fix ("Close the backend gate for suppliers and multi-supplier search", trailers).

---

## Frontend

### Task 10: Suppliers tab: toggles per supplier and product

**Files:**
- Modify: `lib/api/types.ts` (supplier types), `lib/api/enums.ts` (`SupplierProvider = 'TRIPJACK' | 'MOCK'`;
  `SUPPLIER_INTEGRATIONS` adds `{ provider: 'MOCK', label: 'Mock (test)' }`; `SUPPLIER_BASE_URL_HINT:
  Record<SupplierProvider, string> = { TRIPJACK: 'https://api.tripjack.com', MOCK: 'mock://local' }`),
  `lib/api/services/platformSupplier.ts` (delete `updateSupplierProducts`), `components/platform/SupplierModal.tsx`
  (rewrite; delete `SupplierCommissionModal`), `app/platform/settings/suppliers/page.tsx`
- Test: `lib/api/services/platformSupplier.test.ts`, `components/platform/SupplierModal.test.tsx`,
  `app/platform/settings/suppliers/page.test.tsx`

**Interfaces:**
- Consumes: Task 3's JSON.
- Produces:
```ts
export interface SupplierProductSettings { product: PricingProduct; searchEnabled: boolean; bookingEnabled: boolean }
export interface PlatformSupplierCreateRequest { name: string; provider: SupplierProvider; baseUrl: string; apiKey: string; products: SupplierProductSettings[] }
export interface PlatformSupplierUpdateRequest { name: string; baseUrl: string; apiKey?: string; products: SupplierProductSettings[] }
export interface PlatformSupplierResponse {
  id: string; name: string; provider: SupplierProvider; environment: SupplierEnvironment; baseUrl: string;
  maskedApiKey: string; products: SupplierProductSettings[]; updatedAt?: string; updatedBy?: string;
}
```
  `SupplierModal({ supplier?, onClose, onSaved })` (no `available` prop).

- [ ] **Step 1: Write the failing tests**

```tsx
// page.test.tsx
it('shows each supplier with its environment badge, masked key and a search and booking switch per product')
  // "Production" badge for PROD, "UAT" for UAT, "Mock" for MOCK; switches named "Flight search", "Flight booking", "Hotel search", "Hotel booking"
it('a switch saves the whole product set in one PUT', async () => {
  await user.click(screen.getByRole('switch', { name: 'Flight booking' }));
  expect(updateSupplier).toHaveBeenCalledWith('s1', { name: 'Tripjack', baseUrl: 'https://api.tripjack.com',
    products: [{ product: 'FLIGHT', searchEnabled: true, bookingEnabled: true }, { product: 'HOTEL', searchEnabled: false, bookingEnabled: false }] });
});
it('a refused switch flips back and shows the error')
it('Add supplier is always offered') // several rows per integration
// SupplierModal.test.tsx
it('create sends name, integration, base URL, key and the products served')
it('edit shows the integration as text, keeps a blank key and sends no apiKey')
it('refuses to save without a product: "Pick at least one product"')
it('shows the server message on a 400')
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk npx vitest run app/platform/settings/suppliers/page.test.tsx components/platform/SupplierModal.test.tsx lib/api/services/platformSupplier.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement**

Card: name, integration badge, environment badge, masked key, Test connection with its last result, Edit; a row per
product the supplier serves with two `role="switch"` toggles; a toggle PUTs the full product set with that one value
flipped, disables both switches while saving, and on error restores the previous value and shows the message. Modal:
Name, Integration (a `Select` from `SUPPLIER_INTEGRATIONS` on create, plain text on edit), Base URL (placeholder from
`SUPPLIER_BASE_URL_HINT`), API key (required on create; on edit "Leave blank to keep the current key"), and for Flight
and Hotel a "Serves `<product>`" checkbox with its two switches.

- [ ] **Step 4: Run the tests** (Step 2 command). Expected: PASS, 100 % on the three files.

- [ ] **Step 5: Commit** in `travel-crm` ("Switch supplier search and booking per product from the Suppliers tab", trailers).

### Task 11: P&L, statement import, Voyra net and pricing impact leave the screens

**Files:**
- Delete: `app/platform/pnl/page.tsx` and `page.test.tsx`, `components/platform/StatementImportModal.tsx` and its test,
  `lib/api/services/platformPnl.ts` and its test
- Modify: `app/platform/layout.tsx` (+ test; the P&L nav entry and its icon import), `app/platform/dashboard/page.tsx`
  (+ test; the Voyra net tile goes, the GMV tile links to `/platform/bookings`), `components/booking/PlatformBookingCard.tsx`
  (+ test; the three Voyra rows go), `components/booking/PlatformBookingTable.tsx` (+ test; the admin column and line
  show "Agency earns" from `agencyEarns`), `components/platform/PricingRuleModal.tsx` (+ test; no allow-above-earned box,
  no impact line), `app/platform/settings/pricing-rules/page.tsx` (+ test; no "may exceed earned" badge),
  `components/platform/AgencyPricingRulesCard.tsx` (+ test; no preview, no worst case, no Loss-making badge),
  `lib/api/services/pricingRules.ts` (+ test; delete `ruleImpact` and the preview call, no `allowAboveEarned`),
  `lib/api/types.ts` (delete `PnlResponse`, `PnlRow`, `StatementImportResponse`, `StatementLineResponse`,
  `PricingRuleImpactResponse`, `PricingPreviewResponse`; drop `voyraNet30d`, the admin booking's `voyraEarned`,
  `voyraEarnedSource`, `voyraEarnedActual`, `voyraNet`, the rule's `allowAboveEarned`, the assignment's
  `worstCaseNetPer10k` and `lossMaking`)

**Interfaces:**
- Consumes: Tasks 1 and 2's JSON.

- [ ] **Step 1: Write the failing tests**

```tsx
// layout.test.tsx
it('has no P&L entry', () => expect(screen.queryByRole('link', { name: 'P&L' })).toBeNull());
// dashboard/page.test.tsx
it('shows GMV linking to bookings and no Voyra net tile') // tile('GMV (30 d)') href '/platform/bookings'; queryByText('Voyra net (30 d)') null
// PlatformBookingCard.test.tsx
it('shows the admin no Voyra earned or Voyra net row')
// PlatformBookingTable.test.tsx
it('shows the admin what the agency earns') // "Agency earns ₹784.00"; no "Voyra net"
// PricingRuleModal.test.tsx
it('a commission rule has no allow-above-earned box and no impact line')
// AgencyPricingRulesCard.test.tsx
it('shows the assigned rules without a preview or loss badge')
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk npx vitest run app/platform components/booking/PlatformBookingCard.test.tsx components/booking/PlatformBookingTable.test.tsx components/platform lib/api/services/pricingRules.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement** the deletions.

- [ ] **Step 4: Run the tests** (Step 2 command). Expected: PASS.

- [ ] **Step 5: Commit** ("Remove the P&L, statement import, Voyra net and pricing impact screens", trailers).

### Task 12: Flight results by supplier

**Files:**
- Create: `components/flights/supplierFares.ts`, `components/flights/supplierFares.test.ts`
- Modify: `lib/api/types.ts` (flight types), `components/flights/FlightSearchPanel.tsx`,
  `components/flights/FlightSearchPanel.test.tsx`

**Interfaces:**
- Consumes: Tasks 4 and 6's JSON:
```ts
// FlightOfferResponse adds
supplierId: string; supplierName: string;
// FlightGroupResponse adds
cheapestSupplierName: string; supplierCount: number;
// FlightSearchPageRequest adds
suppliers?: string[];
// FlightSearchPageResponse adds
suppliers: SearchSupplier[]; suppliersFailed: string[];
export interface SearchSupplier { supplierId: string; supplierName: string; bookingEnabled: boolean }
```
- Produces: `bySupplier(fares: FlightOfferResponse[], suppliers: SearchSupplier[]): SupplierFares[]` with
  `interface SupplierFares { supplierId: string; supplierName: string; bookingEnabled: boolean; fares: FlightOfferResponse[] }`
  (the first fare's supplier first, the rest by name; a supplier missing from `suppliers` counts as not taking
  bookings); `sameSupplier(groups: FlightGroupResponse[], supplierId: string): FlightGroupResponse[]` (each group's
  fares filtered to that supplier, empty groups dropped).

- [ ] **Step 1: Write the failing tests**

```tsx
// supplierFares.test.ts
it('groups fares by supplier, the leading supplier first')
it('a supplier not in the list is not taking bookings')
it('sameSupplier keeps only that supplier and drops empty rows')
// FlightSearchPanel.test.tsx
it('a row shows its cheapest supplier and +1 more; expanding shows a Book on card per supplier', async () => {
  expect(within(row).getByText(/on Mock supplier/)).toBeVisible();
  await user.click(within(row).getByRole('button', { name: '+1 more' }));
  expect(within(row).getByRole('region', { name: 'Book on Mock supplier' })).toBeVisible();
  expect(within(row).getByRole('region', { name: 'Book on Mock supplier 2' })).toBeVisible();
});
it('a supplier not taking bookings is greyed with "Not taking bookings" and its fares cannot be booked')
it('the Suppliers filter sends the chosen supplier ids') // searchFlightsPage called with suppliers: ['s2']
it('a partial result says "Tripjack did not respond" above the results')
it('picking an onward fare limits the return column to its supplier') // "Return flights on Mock supplier 2 (same supplier as your onward)"
it('with no return on that supplier the column says so') // "No return flights on Mock supplier 2; pick an onward fare on another supplier"
it('a combo row on a split page books on its own, with no return pick')
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk npx vitest run components/flights`
Expected: FAIL.

- [ ] **Step 3: Implement**

Collapsed row: the leading supplier's fares (preview as today), its name in the summary ("on `<name>`") and a "+`N`
more" button when `supplierCount > 1` that opens the row (the existing `openFares` set). Open row: one
`<section aria-label="Book on <name>">` per `bySupplier` entry with that heading and its fare chips; a supplier not
taking bookings is drawn at reduced opacity with "Not taking bookings", its chips selectable for proposals but Book with
Voyra disabled. A Suppliers checkbox filter beside Airlines, from `result.suppliers`, sent as `suppliers` in the view.
The failed-supplier line sits above the results. In split round trips the return column shows
`sameSupplier(returnFlights, onwardPick.f.supplierId)` with the two lines above, and a COMBO group in the onward
column renders with `side = null`. NO_SUPPLIER and SUPPLIERS_UNAVAILABLE show the server message (`errorMessage`).

- [ ] **Step 4: Run the tests** (Step 2 command). Expected: PASS, 100 % on both files.

- [ ] **Step 5: Commit** ("Show every supplier's fares for a flight and book on the one picked", trailers).

### Task 13: Hotels and booking screens name the supplier

**Files:**
- Modify: `lib/api/types.ts` (`HotelOfferResponse` adds `supplierId`, `supplierName`; `HotelSearchPage` adds
  `suppliersFailed: string[]`; `HotelDetailResponse` adds `supplierId`, `supplierName`, `bookingEnabled`;
  `PlatformBookingResponse` adds `supplierName?: string | null`), `components/hotels/HotelSearchPanel.tsx` (+ test),
  `components/hotels/HotelDetail.tsx` (+ test), `components/booking/BookWithVoyraModal.tsx` (+ test; `HotelBookTarget`
  adds `supplierName: string`; the review step shows "Booking on `<name>`" from `fare.supplierName` or the hotel
  target's; `LIVE_BOOKINGS_PAUSED` leaves both copy maps), `components/booking/PlatformBookingCard.tsx` (+ test; a
  "Supplier" row), `components/booking/BookingTrackerModal.tsx` (+ test; "on `<name>`" under the title)

**Interfaces:**
- Consumes: Tasks 5, 7 and 8's JSON.

- [ ] **Step 1: Write the failing tests**

```tsx
// HotelSearchPanel.test.tsx
it('names the supplier on every hotel row and says which supplier did not respond')
// HotelDetail.test.tsx
it('names the supplier and, when it is not taking bookings, disables Book with Voyra with "Not taking bookings"')
// BookWithVoyraModal.test.tsx
it('the review step says "Booking on Mock supplier 2"')
it('SUPPLIER_NOT_BOOKABLE shows the server message') // "Tripjack is not taking bookings right now; pick another supplier"
// PlatformBookingCard.test.tsx / BookingTrackerModal.test.tsx
it('prints the supplier name')
```

- [ ] **Step 2: Run them to verify they fail**

Run: `rtk npx vitest run components/hotels components/booking`
Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run the tests** (Step 2 command). Expected: PASS.

- [ ] **Step 5: Commit** ("Name the supplier on hotels, the booking dialog and the booking card", trailers).

### Task 14: Frontend gate

- [ ] **Step 1:** `rtk npx vitest run --coverage` — Expected: every test passes; thresholds at 100 %.
- [ ] **Step 2:** `rtk npx tsc --noEmit` — Expected: no error.
- [ ] **Step 3:** `rtk npm run lint` — Expected: error count not above the one noted before Task 10, none in a file
  this plan touched.
- [ ] **Step 4:** `rtk npm run build` — Expected: success; no `/platform/pnl` route.
- [ ] **Step 5: Commit** any fix ("Close the frontend gate for suppliers", trailers).

---

## QA, end to end and docs

### Task 15: The mock is a supplier row for local QA and e2e

**Files:**
- Create: `/Users/shivanshsrivastava/Projects/Voyra global/.claude/run/seed-mock-supplier.py` (not in git),
  `e2e/global-setup.ts`
- Modify: `/Users/shivanshsrivastava/Projects/Voyra global/.claude/run/start-backend-mock.sh`,
  `start-backend.sh`, `start-backend-once.sh`, `start-backend-s6.sh` (same folder; drop `FLIGHT_SUPPLIER=mock`,
  `HOTEL_SUPPLIER=mock` and any `*_BOOKING_PROVIDER`), `/Users/shivanshsrivastava/Projects/Voyra global/.claude/agents/regression-tester.md`
  (line 58: drop `FLIGHT_SUPPLIER=mock HOTEL_SUPPLIER=mock`; after the health check add "then run
  `python3 .claude/run/seed-mock-supplier.py` to switch the Mock supplier on"), `e2e/fixtures.ts`, `playwright.config.ts`
  (`globalSetup: './e2e/global-setup.ts'`)

**Interfaces:**
- Consumes: Task 3's supplier API.
- Produces (`e2e/fixtures.ts`):
```ts
export interface E2eSupplier { id: string; name: string; provider: 'TRIPJACK' | 'MOCK'; baseUrl: string;
  products: { product: 'FLIGHT' | 'HOTEL'; searchEnabled: boolean; bookingEnabled: boolean }[] }
/** Finds the MOCK row by name (ignoring case) or creates it; sets both products' search and booking to `on`. */
export async function ensureMockSupplier(request: APIRequestContext, admin: LoginResult, name = 'Mock supplier', on = true): Promise<E2eSupplier>;
/** Switches search off on every supplier whose id is not in `keepIds`. */
export async function onlySuppliersSearching(request: APIRequestContext, admin: LoginResult, keepIds: string[]): Promise<void>;
```
  `PricingRule` loses `allowAboveEarned`; `ensurePricingPair` matches and creates rules by kind, product and pct only.

- [ ] **Step 1: Seed script.** `seed-mock-supplier.py`, in the style of `seed-plan-agencies.py`: `API_BASE` must be
  `http://localhost:8080` or `http://127.0.0.1:8080` (else `SystemExit` "Refusing to run against …"); logs in with
  `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD`; lists suppliers; creates `Mock supplier` (MOCK, `mock://local`, key
  `mock-key`) when absent; PUTs FLIGHT and HOTEL search and booking on. API only, never SQL; prints the row's name and
  toggles, never a key.

- [ ] **Step 2: Fixtures and global setup.** `global-setup.ts` refuses unless `API_BASE`'s host is `localhost` or
  `127.0.0.1`, logs in `PLATFORM_ADMIN`, then `ensureMockSupplier(..., 'Mock supplier', true)`,
  `ensureMockSupplier(..., 'Mock supplier 2', false)` and `onlySuppliersSearching(..., [mock.id])`. Both helpers are
  idempotent (list first, create only when missing).

- [ ] **Step 3: Verify.** Start the backend with `start-backend-mock.sh`, run the seed script twice (the second run
  changes nothing), then `rtk npx playwright test e2e/platform-flight-booking.spec.ts` with the frontend built and
  started. Expected: PASS.

- [ ] **Step 4: Commit** `e2e/fixtures.ts`, `e2e/global-setup.ts`, `playwright.config.ts` in `travel-crm` ("Run e2e on
  the Mock supplier row", trailers). The `.claude/` files are not committed.

### Task 16: Playwright specs

**Files:**
- Create: `e2e/multi-supplier-search.spec.ts`
- Modify: `e2e/platform-supplier.spec.ts` (every test except "General has the Go live card and the booking rules, and
  no JSON settings"), `e2e/agency-pricing.spec.ts` (no impact line, no allow-above-earned), `e2e/platform-flight-booking.spec.ts`
  and `e2e/platform-hotel-booking.spec.ts` (the admin detail no longer matches `/voyraNet/`; it matches
  `"supplierName":"Mock supplier"` and not `/voyraNet|voyraEarned/`; the agency `LEAKS` regex stays)

**Interfaces:**
- Consumes: Task 15's fixtures; the UI copy of Tasks 10, 12, 13.

- [ ] **Step 1: Rewrite `platform-supplier.spec.ts`**

```ts
test('the supplier cards show the masked key, environment and per-product switches, and the old route lands on Settings')
test('a product switch saves at once and survives a reload')   // Hotel booking on the Mock supplier, reload, back off
test('Add supplier sends name, integration, base URL, key and products (create stubbed)')
test('the integration and the environment cannot change')      // API: PUT provider TRIPJACK on the mock row -> 400; a UAT URL on a PROD row -> 400 "Add a new supplier for the production account"
test('rotating the key changes only the masked key')           // PUT apiKey "mock-key-9a7b" -> maskedApiKey "••••••9a7b"; a booking search still answers
```

- [ ] **Step 2: Write `multi-supplier-search.spec.ts`** (`beforeAll`: `ensureMockSupplier(..., 'Mock supplier 2', true)`;
  `afterAll`: the same with `false`)

```ts
test('one row per flight with +1 more and a Book on card per supplier')
test('a booking on Mock supplier 2 is made on Mock supplier 2') // book through the UI; admin GET /api/platform/bookings/{id} supplierName "Mock supplier 2"
test('a supplier not taking bookings is greyed')               // booking off on Mock supplier 2 by API -> its card says "Not taking bookings"; back on after
test('a round trip keeps both legs on one supplier')           // onward on Mock supplier 2 -> "Return flights on Mock supplier 2 (same supplier as your onward)"; booked row supplierName "Mock supplier 2"
test('a review with legs on two suppliers is refused')         // API review with offerId on one row and returnOfferId on the other -> 400 "Both legs must be on one supplier"
```

- [ ] **Step 3: Run the specs**

Run (backend from `start-backend-mock.sh`, frontend built and started on 3000):
`rtk npx playwright test e2e/platform-supplier.spec.ts e2e/multi-supplier-search.spec.ts e2e/agency-pricing.spec.ts e2e/platform-flight-booking.spec.ts e2e/platform-flight-roundtrip.spec.ts e2e/platform-hotel-booking.spec.ts e2e/platform-ticket.spec.ts e2e/unified-bookings.spec.ts e2e/booking-tracker.spec.ts e2e/hotel-search.spec.ts e2e/hotel-quick-book.spec.ts e2e/agency-home.spec.ts`
Expected: PASS (the two unified-bookings journeys that need a mock switch restart are run as matrix row 9h says).

- [ ] **Step 4: Commit** ("Cover suppliers and multi-supplier search end to end", trailers).

### Task 17: Docs, invariants and the regression matrix

**Files:**
- Modify: `/Users/shivanshsrivastava/Projects/Voyra global/CLAUDE.md` (not in git): the slice 3, slice 4, slice 6 and
  Pricing rules bullets (leave slice 5 to the other plan); `travel-crm-backend/PROGRESS.md`; `travel-crm/CLAUDE.md`
  (the platform `dashboard`, Suppliers, `pnl` and pricing-rules lines and the slice 6 line; leave the General tab line
  to the other plan); `/Users/shivanshsrivastava/Projects/Voyra global/.claude/skills/full-regression/scripts/invariants.py`;
  `/Users/shivanshsrivastava/Projects/Voyra global/.claude/skills/full-regression/scenario-matrix.md`

- [ ] **Step 1: Root `CLAUDE.md`.** Slice 3: the provider is the supplier row stamped at review (`credential_id`,
  `environment`), `SupplierRegistry` replaces `BookingProviderRegistry`, no `FLIGHT_BOOKING_PROVIDER`, no Voyra earnings,
  deploy note on toggles. Slice 4: no `HOTEL_BOOKING_PROVIDER`; hotels are not merged across suppliers. Slice 6: no P&L
  or statement endpoints, no live switch or `LIVE_BOOKINGS_PAUSED`, `TripjackLiveSwitchGuard` gone, routing by the
  booking's supplier row (`serves`), dashboard without Voyra net. Pricing rules: no allow-above-earned, no impact or
  preview endpoints. Add a "Suppliers and multi-supplier search (2026-11-01)" bullet: V37, per-product toggles, MOCK
  row, namespaced ids, fan-out (40 s, `NO_SUPPLIER`, `SUPPLIERS_UNAVAILABLE`, `suppliersFailed`), grouping key, the
  rulings of this plan, the seed script.
- [ ] **Step 2: `PROGRESS.md`.** A dated section with the same facts and the spec's deploy notes 1-4 for V37:
  backup first; every toggle is off after V37, so read `FLIGHT_SUPPLIER`, `HOTEL_SUPPLIER`, `FLIGHT_BOOKING_PROVIDER`,
  `HOTEL_BOOKING_PROVIDER` and `tripjack_live_ready` before the deploy and set the Tripjack row's toggles to match
  (production: Flight search on, Flight booking off until wanted, Hotel off), then remove the four variables from Cloud
  Run; count PROD rows in HELD, BOOKING, UNKNOWN or CANCELLING and confirm the Tripjack row is PROD; V37 refuses on a
  capped commission rule above its estimate or a non-GROSS `discount_base`.
- [ ] **Step 3: `invariants.py`.** Delete the two `voyra_net` rules; add "every platform booking past REVIEWING has a
  `credential_id` that exists": `select b.id from public.platform_booking b left join public.platform_supplier_credential c
  on c.id = b.credential_id where b.tenant_id = '{t}' and b.status <> 'REVIEWING' and c.id is null`.
- [ ] **Step 4: `scenario-matrix.md`.** Edit the PB, PH and LH rows that mention the live switch, P&L, statements or
  `voyraNet`; add rows SU1-SU5 (add a supplier per environment, toggle per product, immutable integration and
  environment, key rotation, mock row refused on cloudrun) and MS1-MS6 (grouped rows and "+N more", Book on cards,
  not taking bookings, one supplier per round trip, partial and total supplier failure, hotel rows per supplier).
- [ ] **Step 5: Commit** `PROGRESS.md` in `travel-crm-backend` and `CLAUDE.md` in `travel-crm` ("Document suppliers in
  the database and multi-supplier search", trailers).
