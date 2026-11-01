# Settings and Plan Prices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The platform admin sets the Lite, Premium and extra-seat prices on a Plans tab, the General tab and the
Custom plan disappear, and every agency keeps the monthly price frozen on its subscription when its plan was set.

**Architecture:** Public migration V38 adds `plan_price_monthly` and `seat_price_monthly` to `subscription`, backfills
them and converts CUSTOM rows to LITE or SUITE without changing anyone's bill. Billing reads the frozen prices from the
row; only a plan change (admin edit, trial extension, paid upgrade) writes them. The Plans-tab prices stay in the
`sku_prices` setting (three keys read, the rest ignored) behind a new `GET/PUT /api/platform/plans`.

**Tech Stack:** Spring Boot 3.3 / Java 17, JPA, Flyway, JUnit 5 + Mockito, JaCoCo 100 %; Next.js 15 / React 19,
Vitest (100 % thresholds), Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-11-01-suppliers-plans-and-multi-supplier-search-design.md`,
**Part 3** plus its share of Cross-cutting and Deploy notes. Parts 1 and 2 are a separate plan running in parallel.

Repos: backend `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend`, frontend
`/Users/shivanshsrivastava/Projects/Voyra global/travel-crm`, both on branch `nitin-dev` (or the worktree the executor
was given). Backend paths below are under `src/main/java/com/voyra/crm/` unless they start with `src/`; frontend paths
are relative to `travel-crm/`.

## Global Constraints

- Never push; never touch `main`. Commit per task in the repo the task changed, prose message, ending with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Qq6uXt8Qha7QvtoJUmYRa9
  ```
- Prefix shell commands with `rtk`. Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"`.
  Each backend task ends with `rtk ./mvnw -q test -Dtest=<its tests>` and
  `scripts/coverage-gaps.sh '<same pattern>' <touched class or package>` showing no missed line, branch or method.
- **Migration number is V38** (`V38__plan_prices.sql`), expand only. V37 belongs to the other stream; the drops
  (old `sku_prices` keys, the column defaults) are V39 in a later release, not here.
- **Never apply V38 to the shared local database before V37 is in it.** Flyway here has no out-of-order, so a local
  database at V38 without V37 refuses to start once V37 arrives. Check V38 on Testcontainers and on a scratch copy
  (Task 1); run this branch's backend against the shared database only after the other stream's V37 is applied there.
- Hands off (the other stream edits them): anything under `service/booking/`, `PlatformSupplierCredential*`,
  `AgencyPriceQuoter`, `AgencyPricingService`, `PlatformPnl*`, `TripjackStatement*`, `PlatformDashboardService`, the
  provider classes, `FlightSearch*`, `HotelSearch*`, `app/platform/settings/suppliers/*`,
  `components/platform/SupplierModal.tsx`, `app/platform/pnl/*`, `app/platform/layout.tsx`, `components/flights/*`,
  `components/hotels/*`, `components/booking/*`. In `PlatformSettingService` change only the `sku_prices` constant,
  reader and validation case. In `ControllerSkuTable` only add the `PlatformPlanController` entry. In
  `e2e/platform-supplier.spec.ts` only delete the General-tab test.
- Plan-price JSON shape, everywhere (API, setting keys, frontend type): `{ "LITE": 2999, "SUITE": 4999, "STAFF_SEATS": 399 }`
  (monthly INR, before GST). Each > 0, at most 1000000, at most two decimals.
- Exact copy: Plans-tab note "Annual billing is 10 months. Changing a price affects new subscriptions and upgrades
  only; agencies already on a plan keep their price." Row labels "Voyra CRM Lite", "Voyra Premium", "Extra seat per
  month". Saved "Plan prices saved." Client refusal "Each price must be more than 0, with at most two decimals."
- Frontend: `rtk npx vitest run <files>` per task. Before Task 8 run `rtk npm run lint` once and note its error count;
  it must not rise. UI follows `travel-crm/UI_BLUEPRINT.md` and `components/ui` primitives.

## Rulings made while planning

1. **New columns get `DEFAULT 0`.** The spec says NOT NULL with backfill; during the rolling deploy an old instance
   still creates Basic agencies without the columns, so the default keeps provisioning working. It matches STARTER's 0.
2. **A converted CUSTOM row keeps its exact bill.** Plan price = its non-seat SKU prices + `LEAST(seats, included) ×
   STAFF_SEATS` (when it held STAFF_SEATS), with the new extra seats `GREATEST(seats - included, 0)`; plan + extra
   seats then equals today's renewal to the paisa.
3. **The seat price of an upgrade is stamped on the invoice.** An upgrade with no extra seats has no seat line, so
   `raiseUpgrade` writes `billingDetails.seatPriceMonthly` (today's STAFF_SEATS price) and `settle` freezes it. An
   upgrade invoice raised before this release has no stamp: its plan price comes from its plan line and the seat price
   stays as it was.
4. **New Basic rows freeze 0 and 0** (`EntitlementService.newBasic` is static and has no settings). STARTER prices are
   never billed, and every move off STARTER sets both prices.
5. **Prices are set only when the plan changes,** in `update` and in `extendTrial` (an agency already on SUITE keeps
   its price when a trial is extended).
6. **Plan prices are written by a new `PlatformPlanService`,** not `PlatformSettingService.set`, so the change is
   audited once as `PLAN_PRICES_SET` and the other `sku_prices` keys stay for old instances.

## Review Focus

1. Rolling deploy: an old instance inserts a subscription without the new columns. Expected: the insert succeeds (0, 0). Pinned in Task 1, step 4.
2. A CUSTOM row with no priced SKU converts to LITE at a plan price of 0. Expected: the daily job raises no renewal and logs no error (not "Nothing to bill" every day). Pinned in Task 5, `aZeroPricedPlanRaisesNoRenewal`.
3. An upgrade invoice raised before the deploy and paid after it. Expected: the plan price comes from its line and the seat price is kept, no exception. Pinned in Task 5, `payingAnOldUpgradeInvoiceWithoutTheSeatStampKeepsTheSeatPrice`.
4. A Plans-tab PUT with `2999.999`, `0`, a negative number, a missing key or a string. Expected: 400, nothing stored (no silent rounding). Pinned in Task 4, `planPricesAreValidated`.
5. The generic `PUT /api/platform/settings/sku_prices`, which has no screen, stores JSON without `SUITE`. Expected: refused, because every subscription page and upgrade would fail on it. Pinned in Task 3, `skuPricesNeedLiteSuiteAndSeatsAboveZeroAndIgnoreOtherKeys`.

---

## Backend

### Task 1: V38 frozen-price columns and CUSTOM conversion

**Files:**
- Create: `src/main/resources/db/migration/public/V38__plan_prices.sql`
- Modify: `entity/Subscription.java` (two fields; fix the `skus` and `seats` doc comments, which mention CUSTOM)
- Modify: `service/EntitlementService.java` (`newBasic` only)
- Test: `src/test/java/com/voyra/crm/service/EntitlementServiceTest.java`

**Interfaces:**
- Produces: `Subscription.planPriceMonthly` and `Subscription.seatPriceMonthly` (`BigDecimal`, columns
  `plan_price_monthly` / `seat_price_monthly`, `precision = 12, scale = 2, nullable = false`), builder and getters/setters via Lombok.

- [ ] **Step 1: Write the failing test** `newBasicFreezesZeroPrices` in `EntitlementServiceTest`:

```java
Subscription s = EntitlementService.newBasic("S1", "T1", NOW);
assertThat(s.getPlanPriceMonthly()).isEqualByComparingTo("0");
assertThat(s.getSeatPriceMonthly()).isEqualByComparingTo("0");
```

- [ ] **Step 2: Run it.** `rtk ./mvnw -q test -Dtest=EntitlementServiceTest#newBasicFreezesZeroPrices` → FAIL (no such getter).

- [ ] **Step 3: Add the two entity fields, set both to `BigDecimal.ZERO` in `newBasic`, and write V38:**

```sql
-- Plan prices (2026-11-01, spec Part 3). Expand only; V39 (next release) drops what this stops reading.
-- DEFAULT 0: an old instance still creates Basic agencies without these columns during the rolling deploy.
ALTER TABLE subscription
    ADD COLUMN plan_price_monthly NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (plan_price_monthly >= 0),
    ADD COLUMN seat_price_monthly NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (seat_price_monthly >= 0);

DO $$
DECLARE
    p JSONB := COALESCE((SELECT value::jsonb FROM platform_setting WHERE key = 'sku_prices'),
        '{"CRM_CORE":2499,"STAFF_SEATS":399,"ACCOUNTS_GST":1999,"REPORTS":699,"VISA":499,"SUITE":4999,"LITE":2999}'::jsonb);
    seat NUMERIC := (p ->> 'STAFF_SEATS')::numeric;
    r RECORD; target TEXT; included INT; price NUMERIC;
BEGIN
    UPDATE subscription SET seat_price_monthly = seat,
        plan_price_monthly = CASE plan WHEN 'LITE' THEN (p ->> 'LITE')::numeric
                                       WHEN 'SUITE' THEN (p ->> 'SUITE')::numeric ELSE 0 END
    WHERE plan <> 'CUSTOM';
    FOR r IN SELECT id, tenant_id, skus, seats FROM subscription WHERE plan = 'CUSTOM' FOR UPDATE LOOP
        target := CASE WHEN 'ACCOUNTS_GST' = ANY (r.skus) THEN 'SUITE' ELSE 'LITE' END;
        included := CASE target WHEN 'SUITE' THEN 5 ELSE 3 END;
        -- today's renewal, unchanged: the non-seat SKUs plus the seats the plan now includes
        price := COALESCE((SELECT SUM((p ->> s)::numeric) FROM unnest(r.skus) s WHERE s <> 'STAFF_SEATS' AND p ? s), 0)
               + CASE WHEN 'STAFF_SEATS' = ANY (r.skus) THEN LEAST(r.seats, included) * seat ELSE 0 END;
        UPDATE subscription SET plan = target,
            seats = CASE WHEN 'STAFF_SEATS' = ANY (r.skus) THEN GREATEST(r.seats - included, 0) ELSE 0 END,
            skus = CASE target WHEN 'SUITE' THEN ARRAY['CRM_CORE','STAFF_SEATS','ACCOUNTS_GST','REPORTS','VISA']
                               ELSE ARRAY['CRM_CORE','STAFF_SEATS','REPORTS','VISA'] END,
            plan_price_monthly = price, seat_price_monthly = seat, updated_at = now()
        WHERE id = r.id;
        RAISE NOTICE 'V38: agency % moved from CUSTOM % (% seats) to % at % a month', r.tenant_id, r.skus, r.seats, target, price;
    END LOOP;
END $$;

ALTER TABLE subscription DROP CONSTRAINT subscription_plan_check;
ALTER TABLE subscription ADD CONSTRAINT subscription_plan_check CHECK (plan IN ('STARTER','LITE','SUITE'));
```

- [ ] **Step 4: Check V38 on a scratch copy of the local database** (DB_URL host must be `localhost`; never the shared
  database itself, see Global Constraints). `createdb voyra_v38`, `pg_dump <local db> | psql -q voyra_v38`, record
  `SELECT tenant_id, plan, skus, seats FROM subscription WHERE plan = 'CUSTOM'`, then
  `psql -v ON_ERROR_STOP=1 voyra_v38 -f src/main/resources/db/migration/public/V38__plan_prices.sql`. Expected: one
  NOTICE per former CUSTOM row (locally one, becoming LITE); `SELECT count(*) FROM subscription WHERE plan='CUSTOM'` = 0;
  LITE and SUITE rows carry the `sku_prices` LITE / SUITE value, STARTER 0, every row `seat_price_monthly` = STAFF_SEATS;
  for the converted row `plan_price_monthly + seats * seat_price_monthly` equals its old bill worked out by hand from
  the recorded SKUs and seats. Old-instance insert:
  delete one STARTER row and re-insert it without the new columns,
  `INSERT INTO subscription (id, tenant_id, plan, skus, seats, status, billing_cycle, updated_at) VALUES ('v38chk', '<that tenant id>', 'STARTER', '{}', 0, 'ACTIVE', 'MONTHLY', now())`:
  it succeeds with 0 / 0. `INSERT ... plan 'CUSTOM'` fails on `subscription_plan_check`. Then `dropdb voyra_v38`.

- [ ] **Step 5: Run the test and the Testcontainers IT** (Flyway applies every public migration to a fresh database):
  `rtk ./mvnw -q test -Dtest=EntitlementServiceTest` → PASS, then with the colima variables from the root CLAUDE.md
  (`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock DOCKER_API_VERSION=1.41 TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`)
  `rtk ./mvnw -q test -Dtest=TenantIsolationIT -Dapi.version=1.41` → PASS.

- [ ] **Step 6: Commit** (backend): "Add frozen plan and seat prices to subscriptions and convert Custom plans in V38" + trailers.

### Task 2: Custom plan removed from the backend

**Files:**
- Modify: `enums/SubscriptionPlan.java` (`STARTER, LITE, SUITE`; doc comment), `service/EntitlementService.java`
  (`planSkus`, `compute`; delete `parse`), `dto/SubscriptionUpdateRequest.java` (delete `skus`), `service/SubscriptionService.java`
  (`update`), `service/SubscriptionBillingService.java` (`rank`, the CUSTOM arm of `lines`, delete `label`),
  schema text in `dto/AgencySubscriptionResponse.java`, `dto/SubscriptionAdminResponse.java`, `controller/AgencySubscriptionController.java`
  ("STARTER < LITE < SUITE")
- Test: `EntitlementServiceTest`, `EntitlementServiceCoverageTest`, `SubscriptionServiceTest`, `SubscriptionBillingServiceTest`,
  `PlatformAgencySubscriptionControllerTest`, `security/EntitlementInterceptorTest`, `AgentSeatLimitTest`

**Interfaces:**
- Produces: `SubscriptionPlan { STARTER, LITE, SUITE }`; `SubscriptionUpdateRequest(plan, seats, billingCycle)` (an old
  client's `skus` is ignored by Jackson); `EntitlementService.planSkus(plan)` is the only source of a row's `skus`.

- [ ] **Step 1: Rewrite the CUSTOM tests onto the fixed plans.**
  - `EntitlementServiceTest`: delete the CUSTOM-only cases (unknown SKU names skipped, CUSTOM seats need STAFF_SEATS,
    CUSTOM read-only, cached CUSTOM); add `liteAndSuiteIgnoreTheStoredSkuList`: a LITE row with `skus(List.of("VISA"))`
    and `seats(2)` resolves to exactly `CRM_CORE, STAFF_SEATS, REPORTS, VISA` and 5 seats; a SUITE row with `skus(List.of())`
    resolves to every SKU. Same in `EntitlementServiceCoverageTest` (line ~61).
  - `SubscriptionServiceTest`: the CUSTOM update tests become `updateStoresThePlansOwnSkus`: LITE with 3 seats ANNUAL
    stores `skus == EntitlementService.planSkus(LITE)`, seats 3, ANNUAL; delete "Add the Staff seats SKU" and the
    duplicate-SKU cases; the `update(...)` helper loses its `skus` parameter.
  - `SubscriptionBillingServiceTest`: delete `aCustomPlanNamesEachSkuAndSkipsSeatsItDoesNotHold`,
    `customWithoutSkusHasNothingToBill`, the CUSTOM half of `aCustomPlanWithNoSkuListAndABasicRenewalHaveNothingToBill`
    (keep the Basic half, renamed `aBasicRenewalHasNothingToBill`); `annualIsTenMonthlyPricesForTwelveMonths` moves to
    LITE with 3 extra seats; `rank` test (if any) expects STARTER 0, LITE 1, SUITE 2.
  - `PlatformAgencySubscriptionControllerTest` line ~69: body `{"plan":"LITE","skus":["CRM_CORE"],"seats":1,"billingCycle":"MONTHLY"}`
    still answers 200 (stray `skus` ignored) and `$.plan` is `LITE`; `{"plan":"CUSTOM",...}` is 400.
  - `EntitlementInterceptorTest` line ~108 and `AgentSeatLimitTest` line ~100: build the `Entitlements` with
    `SubscriptionPlan.LITE` (the record carries any SKU set; the plan name is not what they test).

- [ ] **Step 2: Run them.** `rtk ./mvnw -q test -Dtest='Entitlement*Test,Subscription*Test,PlatformAgencySubscriptionControllerTest,AgentSeatLimitTest'` → compile failure / FAIL.

- [ ] **Step 3: Remove CUSTOM.** `compute` keeps its early STARTER return, then uses
  `plan == SUITE ? EnumSet.allOf(Sku.class) : LITE_SKUS` and `(plan == SUITE ? SUITE_INCLUDED_SEATS : LITE_INCLUDED_SEATS) + extra`
  (a two-way choice, so no unreachable switch arm under JaCoCo). `planSkus` switches over the three plans.
  `SubscriptionService.update` sets `skus = EntitlementService.planSkus(plan)` and drops the STAFF_SEATS check.
  `rank`: `STARTER -> 0; LITE -> 1; SUITE -> 2`. `lines` keeps only its LITE/SUITE branch for now (Task 5 rewrites it).
  Deleting `label()` is safe for old Custom invoices: `SubscriptionInvoicePdfRenderer` prints each stored line's
  `description`, so their PDFs keep their labels (no renderer change).

- [ ] **Step 4: Run step 2 again** → PASS; coverage-gaps on `com.voyra.crm.service.EntitlementService`,
  `SubscriptionService`, `SubscriptionBillingService` clean.

- [ ] **Step 5: Commit** (backend): "Remove the Custom plan: Lite and Premium carry their fixed SKUs" + trailers.

### Task 3: Plan prices read from `sku_prices`

**Files:**
- Create: `models/PlanPrices.java`
- Modify: `service/PlatformSettingService.java` (`SKU_PRICE_KEYS` replaced by `PLAN_PRICE_KEYS`, a new `planPrices()`, the `SKU_PRICES` validation case only)
- Test: `PlatformSettingServiceTest`, `PlatformSettingServiceCoverageTest`

**Interfaces:**
- Produces: `public record PlanPrices(BigDecimal lite, BigDecimal suite, BigDecimal staffSeats)` with
  `public BigDecimal monthly(SubscriptionPlan plan)` (STARTER → `BigDecimal.ZERO`, LITE → `lite`, SUITE → `suite`);
  `PlatformSettingService.planPrices(): PlanPrices` (`@Transactional(readOnly = true)`); package-private
  `static final List<String> PLAN_PRICE_KEYS = List.of("LITE", "SUITE", "STAFF_SEATS")`. `skuPrices()` stays until Task 6.

- [ ] **Step 1: Write the failing tests** in `PlatformSettingServiceTest`:

```java
@Test void planPricesReadLiteSuiteAndSeatsFromSkuPrices() {
    PlanPrices p = service().planPrices();   // DEFAULTS
    assertThat(p.lite()).isEqualByComparingTo("2999");
    assertThat(p.suite()).isEqualByComparingTo("4999");
    assertThat(p.staffSeats()).isEqualByComparingTo("399");
    assertThat(p.monthly(SubscriptionPlan.STARTER)).isEqualByComparingTo("0");
    assertThat(p.monthly(SubscriptionPlan.LITE)).isEqualByComparingTo("2999");
    assertThat(p.monthly(SubscriptionPlan.SUITE)).isEqualByComparingTo("4999");
}
@Test void planPricesFailLoudlyWhenAStoredKeyIsMissing() {
    // stored row {"LITE":2999,"STAFF_SEATS":399} (repository stub, bypassing validation)
    assertThatThrownBy(() -> service().planPrices())
        .isInstanceOf(IllegalStateException.class).hasMessage("sku_prices has no price for SUITE");
}
@Test void skuPricesNeedLiteSuiteAndSeatsAboveZeroAndIgnoreOtherKeys() {
    String ok = "{\"LITE\":2999,\"SUITE\":4999,\"STAFF_SEATS\":399}";
    assertThat(service().set("sku_prices", ok).getValue()).isEqualTo(ok);
    service().set("sku_prices", "{\"LITE\":2999,\"SUITE\":4999,\"STAFF_SEATS\":399,\"CRM_CORE\":2499,\"FOO\":-1}"); // accepted
    for (String bad : List.of("{\"LITE\":2999,\"STAFF_SEATS\":399}", "{\"LITE\":0,\"SUITE\":4999,\"STAFF_SEATS\":399}",
            "{\"LITE\":1000001,\"SUITE\":4999,\"STAFF_SEATS\":399}", "{\"LITE\":2999.999,\"SUITE\":4999,\"STAFF_SEATS\":399}",
            "{\"LITE\":null,\"SUITE\":4999,\"STAFF_SEATS\":399}", "not json"))
        assertThatThrownBy(() -> service().set("sku_prices", bad)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sku_prices");
}
```

  Replace `skuPricesMustNameEveryPriceWithANonNegativeNumber` with the last test, and in
  `PlatformSettingServiceCoverageTest` update the valid `sku_prices` fixture at line ~199 (its `"LITE":0` is now
  refused) and the `skuPricesNeedEveryKeyEachInRange` messages.

- [ ] **Step 2: Run them.** `rtk ./mvnw -q test -Dtest='PlatformSettingService*Test'` → FAIL.

- [ ] **Step 3: Implement.** The validation message, for every refusal of `sku_prices`:
  "sku_prices needs LITE, SUITE and STAFF_SEATS, each a monthly INR price above 0 and at most 1000000, with at most two decimals"
  (keys other than the three are not checked). `planPrices()` parses the stored JSON once and throws
  `IllegalStateException("sku_prices has no price for " + key)` for the first missing key in `PLAN_PRICE_KEYS` order.

- [ ] **Step 4: Run step 2 again** → PASS; coverage-gaps on `PlatformSettingService` and `PlanPrices` clean.

- [ ] **Step 5: Commit** (backend): "Read plan prices from sku_prices: Lite, Premium and the extra seat, other keys ignored" + trailers.

### Task 4: `GET/PUT /api/platform/plans`

**Files:**
- Create: `dto/PlanPricesRequest.java`, `dto/PlanPricesResponse.java`, `service/PlatformPlanService.java`, `controller/PlatformPlanController.java`
- Modify: `enums/PlatformAuditAction.java` (add `PLAN_PRICES_SET`), `security/ControllerSkuTable.java` (add `entry(PlatformPlanController.class, NEVER_GATED)` beside `PlatformSettingController`)
- Test: `src/test/java/com/voyra/crm/service/PlatformPlanServiceTest.java`, `src/test/java/com/voyra/crm/controller/PlatformPlanControllerTest.java`

**Interfaces:**
- Consumes: `PlatformSettingService.planPrices()`, `PlatformSettingService.SKU_PRICES`, `PlatformSettingRepository`, `PlatformAuditService.record`.
- Produces: `PlanPricesRequest` / `PlanPricesResponse` with fields `lite`, `suite`, `staffSeats` (`BigDecimal`)
  serialised as `LITE`, `SUITE`, `STAFF_SEATS` (`@JsonProperty`). Request fields each `@NotNull(message = "<KEY> is required")`,
  `@DecimalMin(value = "0", inclusive = false, message = "<KEY> must be more than 0")`,
  `@DecimalMax(value = "1000000", message = "<KEY> must be at most 1000000")`,
  `@Digits(integer = 7, fraction = 2, message = "<KEY> can have at most two decimals")`.
  `PlatformPlanService.get(): PlanPricesResponse`; `@Transactional PlatformPlanService.set(PlanPricesRequest): PlanPricesResponse`.
  Controller `@RequestMapping("/api/platform/plans")`, class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`,
  `@Tag(name = "Platform - Plans")`, `GET` and `PUT` (`@Valid @RequestBody`), no logic.

- [ ] **Step 1: Write the failing tests.**
  - `PlatformPlanServiceTest.setMergesTheThreePricesKeepsOtherKeysAndAuditsOnce`: stored row
    `{"CRM_CORE":2499,"STAFF_SEATS":399,"SUITE":4999,"LITE":2999}`, `planPrices()` stub (2999, 4999, 399), request
    (3499, 5999, 449) → the saved row's JSON parses to `CRM_CORE` 2499, `LITE` 3499, `SUITE` 5999, `STAFF_SEATS` 449;
    `updatedBy` is the current user id; `auditService.record(PLAN_PRICES_SET, "PLATFORM_SETTING", "sku_prices",
    Map.of("from", Map.of("LITE","2999.00","SUITE","4999.00","STAFF_SEATS","399.00"), "to", Map.of("LITE","3499.00","SUITE","5999.00","STAFF_SEATS","449.00")))`
    exactly once and never `SETTING_SET`; the response carries the new three.
  - `setWithNoStoredRowWritesTheThreePrices`: `findById` empty → saved JSON has exactly the three keys.
  - `anUnreadableStoredValueFailsLoudly`: stored `"{oops"` → `IllegalStateException`, nothing saved.
  - `getReturnsThePlansTabPrices`: `planPrices()` stub → response LITE/SUITE/STAFF_SEATS.
  - `PlatformPlanControllerTest` (pattern of `PlatformSettingControllerTest`):
    `superAdminReadsAndSetsPlanPrices` (GET → `$.LITE` 2999, `$.SUITE` 4999, `$.STAFF_SEATS` 399; PUT same shape → 200);
    `planPricesAreValidated` (PUT with `"LITE":0`, `-1`, `2999.999`, `"abc"`, and without `STAFF_SEATS` → 400 each;
    `verify(service, never()).set(any())`); `agencyOwnerIsForbidden` (GET and PUT → 403).
  - `ControllerSkuTableContractTest` needs no edit (it derives `/api/platform` controllers); it must stay green.

- [ ] **Step 2: Run them.** `rtk ./mvnw -q test -Dtest='PlatformPlan*Test,ControllerSkuTableContractTest'` → FAIL.

- [ ] **Step 3: Implement.** `set` reads the `sku_prices` row through the repository (empty map when absent), parses it
  as `Map<String, Object>` with the `ObjectMapper` (a `JsonProcessingException` becomes
  `IllegalStateException("sku_prices is not valid JSON")`), puts the three prices at scale 2, saves `value`,
  `updatedBy`, `updatedAt`, and audits as in the test. `from` comes from `planPrices()` read before the write.

- [ ] **Step 4: Run step 2 again** → PASS; `rtk ./scripts/blueprint-audit.sh` → passes (controller classified);
  coverage-gaps on `PlatformPlanService`, `PlatformPlanController` clean.

- [ ] **Step 5: Commit** (backend): "Add the platform Plans API for the Lite, Premium and extra-seat prices" + trailers.

### Task 5: Billing uses the frozen prices

**Files:**
- Modify: `service/SubscriptionBillingService.java` (`lines`, `line`, `issue`, `raiseRenewal`, `raiseUpgrade`, `settle`)
- Test: `SubscriptionBillingServiceTest` (fixture `suite()` gets `.planPriceMonthly(new BigDecimal("4999")).seatPriceMonthly(new BigDecimal("399"))`;
  the upgrade-settle fixtures get a plan line; `PRICES` / `skuPrices()` stubs go, `planPrices()` stubs come in),
  `SubscriptionLifecycleServiceTest`, `SubscriptionPaymentServiceTest` (fixtures only, if they build lines)

**Interfaces:**
- Consumes: `Subscription.planPriceMonthly / seatPriceMonthly` (Task 1), `PlatformSettingService.planPrices()` and `PlanPrices.monthly` (Task 3).
- Produces: `public static List<Map<String, Object>> lines(Subscription s)` (no prices parameter): STARTER → empty;
  LITE/SUITE → the plan line (`sku` "LITE"/"SUITE", same descriptions as today, quantity 1, unit = `planPriceMonthly`)
  plus, when seats > 0, `STAFF_SEATS` "Extra staff seats" × seats at `seatPriceMonthly`; months 10 for ANNUAL, else 1.
  A null frozen price → `IllegalStateException("No frozen price for " + sku + " on subscription " + id)`.
  Upgrade invoices carry `billingDetails.seatPriceMonthly` (plain string, scale 2).

- [ ] **Step 1: Write the failing tests:**

```java
@Test void linesBillTheFrozenPricesNotTheSetting() {
    Subscription s = suite(2, MONTHLY).toBuilder().planPriceMonthly(new BigDecimal("4500")).seatPriceMonthly(new BigDecimal("350")).build();
    List<Map<String, Object>> lines = SubscriptionBillingService.lines(s);
    assertThat(amount(lines.get(0))).isEqualByComparingTo("4500");
    assertThat(lines.get(1)).containsEntry("unitPrice", "350.00").containsEntry("quantity", 2);
    assertThat(service.issue(s, START, START).getAmount()).isEqualByComparingTo("5200.00");
    verify(settingService, never()).planPrices();
}
@Test void aMissingFrozenPriceIsRefused()   // planPriceMonthly(null) -> "No frozen price for SUITE on subscription S1"
@Test void aZeroPricedPlanRaisesNoRenewal() // LITE, frozen 0 / 399, seats 0, periodEnd now -> raiseRenewal empty, never saved, no exception
@Test void anUpgradeIsPricedAtTodaysPlansTabPricesAndStampsTheSeatPrice() {
    // STARTER row frozen 0 / 0; planPrices() -> (3499, 5999, 449)
    SubscriptionInvoiceResponse r = service.raiseUpgrade("T1", SubscriptionPlan.LITE);
    // saved invoice: lines[0] sku LITE, unitPrice "3499.00", description "Voyra CRM Lite (3 staff seats included)";
    // billingDetails.seatPriceMonthly "449.00"; targetPlan LITE; the subscription still STARTER with 0 / 0
}
@Test void payingAnUpgradeFreezesTheInvoicesOwnPrices() {
    // invoice targetPlan SUITE, lines [{sku SUITE, unitPrice "5999.00"}], billingDetails seatPriceMonthly "449.00";
    // planPrices() would answer (1, 1, 1) -> after markPaidByBank: plan SUITE, planPriceMonthly 5999.00, seatPriceMonthly 449.00
    verify(settingService, never()).planPrices();
}
@Test void payingAnOldUpgradeInvoiceWithoutTheSeatStampKeepsTheSeatPrice() // no billingDetails key -> plan from its line, seat stays 399
@Test void anUpgradeInvoiceWithoutItsPlanLineFailsLoudly() // -> IllegalStateException "Upgrade invoice VOY-SUB-2026-1 has no SUITE line"
@Test void payingARenewalLeavesTheFrozenPricesAlone()      // targetPlan null -> planPriceMonthly and seatPriceMonthly unchanged
```

  Existing arithmetic tests (`suiteMonthly...`, `liteIs...`, `annualIsTen...`, `gstRoundsHalfUp...`) call `lines(s)`
  and keep their expected amounts through the fixture's frozen 4999 / 2999 / 399. `aSkuWithoutAPriceIsRefused` becomes
  `aMissingFrozenPriceIsRefused`.

- [ ] **Step 2: Run them.** `rtk ./mvnw -q test -Dtest='Subscription*Test'` → FAIL.

- [ ] **Step 3: Implement.** `issue` and `raiseRenewal` call `lines(s)`; `raiseRenewal` returns empty when the lines'
  total is zero (replaces `lines(...).isEmpty()`; STARTER is still caught). `raiseUpgrade` reads `planPrices()` once,
  prices the `priced` subscription with `monthly(wanted)` and `staffSeats()`, and puts `seatPriceMonthly` into the
  issued invoice's `billingDetails` before saving. In `settle`'s upgrade branch, set `planPriceMonthly` from the
  `unitPrice` of the line whose `sku` equals the target plan's name, and `seatPriceMonthly` from
  `billingDetails.seatPriceMonthly` when present.

- [ ] **Step 4: Run step 2 again** → PASS; coverage-gaps on `SubscriptionBillingService` clean.

- [ ] **Step 5: Commit** (backend): "Bill subscriptions at their frozen prices and freeze what a paid upgrade charged" + trailers.

### Task 6: Plan changes freeze prices; the agency sees its price

**Files:**
- Modify: `service/SubscriptionService.java` (`update`, `extendTrial`, `agencyView`, `planPrices`), `dto/AgencySubscriptionResponse.java`,
  `service/PlatformSettingService.java` (delete `skuPrices()`)
- Test: `SubscriptionServiceTest`, `PlatformSettingServiceTest`, `PlatformSettingServiceCoverageTest` (drop the `skuPrices()` assertions)

**Interfaces:**
- Consumes: `PlatformSettingService.planPrices()`, `PlanPrices.monthly`.
- Produces: `AgencySubscriptionResponse.currentPlanPriceMonthly` (`BigDecimal`, schema "Monthly price before GST this
  agency is billed for its plan, frozen when the plan was set; 0 on Voyra Basic"); `planPrices` keeps
  `{LITE, SUITE}` in that order, from `planPrices()`.

- [ ] **Step 1: Write the failing tests** in `SubscriptionServiceTest` (stub `planPrices()` → (3499, 5999, 449); the
  base row is LITE frozen 2999 / 399):
  - `changingThePlanFreezesTodaysPlansTabPrices`: update to SUITE → row 5999 / 449; the `SUBSCRIPTION_CHANGE` audit's
    `before` has `planPriceMonthly` "2999.00" and `after` "5999.00".
  - `changingOnlySeatsOrCycleKeepsTheFrozenPrices`: update LITE → LITE, seats 2, ANNUAL → 2999 / 399;
    `verify(settingService, never()).planPrices()`.
  - `movingToBasicFreezesZeroAndTodaysSeatPrice`: update to STARTER → 0 / 449.
  - `extendTrialFreezesSuitePricesWhenItChangesThePlan`: LITE row → SUITE trial at 5999 / 449; a SUITE row frozen 4500 → keeps 4500 / 399.
  - `agencyViewShowsTheFrozenPriceAndTodaysUpgradePrices`: `currentPlanPriceMonthly` 2999 and
    `planPrices` `{LITE=3499, SUITE=5999}` with keys in order LITE, SUITE.
  - Rename `anExpiredWaiverIsNotShownAsWaivedAndOnlyPricedPlansAreOffered` to `anExpiredWaiverIsNotShownAsWaived`
    (every plan now has a price).

- [ ] **Step 2: Run them.** `rtk ./mvnw -q test -Dtest='SubscriptionServiceTest,PlatformSettingService*Test'` → FAIL.

- [ ] **Step 3: Implement.** In `update` and `extendTrial`, when the new plan differs from the row's, set both prices
  from `planPrices()` (`monthly(plan)`, `staffSeats()`); add `planPriceMonthly` and `seatPriceMonthly` (plain strings)
  to the audit's before and after maps. `agencyView` sets `currentPlanPriceMonthly(s.getPlanPriceMonthly())`.
  Delete `skuPrices()` once nothing calls it (`grep -rn skuPrices src/main` empty).

- [ ] **Step 4: Run step 2 again** → PASS; coverage-gaps on `SubscriptionService`, `PlatformSettingService` clean.

- [ ] **Step 5: Commit** (backend): "Freeze plan prices on every plan change and show the agency the price it pays" + trailers.

### Task 7: Backend gate

**Files:** none new (fix whatever the gate finds in the files of Tasks 1-6).

- [ ] **Step 1: Run** `rtk ./mvnw verify` → BUILD SUCCESS (all tests, JaCoCo 100 % line/branch/method, `blueprint-audit.sh`).
- [ ] **Step 2: Run the ITs** with the colima variables: `rtk ./mvnw -q test -Dtest=TenantIsolationIT,ProposalLinkResolutionIT -Dapi.version=1.41` → PASS.
- [ ] **Step 3: Check the hands-off list:** `git diff --name-only <task-1 parent>..HEAD` lists none of the Global
  Constraints' hands-off files, and `PlatformSettingService` / `ControllerSkuTable` diffs touch only the parts allowed.
- [ ] **Step 4: Commit** any gate fixes (backend): "Close coverage gaps from the plan prices work" + trailers (skip if nothing changed).

---

## Frontend

### Task 8: Custom plan removed from the UI

**Files:**
- Modify: `lib/api/types.ts` (`SubscriptionPlan` loses `'CUSTOM'`; `SubscriptionUpdateRequest` loses `skus`),
  `lib/plans.ts` (`ServerPlan`, `SERVER_PLAN_LABEL`, `tierOf`, header comment), `lib/entitlements.ts` (`Entitlements.plan`),
  `components/platform/AgencySubscriptionCard.tsx` (`PLAN_LABEL`, `EditForm`), callers of `tierOf`:
  `app/agency/subscription/page.tsx` (two), `components/home/AgencyHome.tsx`
- Test: `lib/plans.test.ts`, `components/platform/AgencySubscriptionCard.test.tsx`, any test passing a second argument to `tierOf`

**Interfaces:**
- Produces: `tierOf(plan: ServerPlan | string | undefined | null): PlanTier` (the `skus` parameter goes);
  `ServerPlan = 'STARTER' | 'LITE' | 'SUITE'`; `updateAgencySubscription(tenantId, { plan, seats, billingCycle })`.

- [ ] **Step 1: Update the tests.**
  - `plans.test.ts`: `SERVER_PLAN_LABEL` equals `{ STARTER: 'Voyra Basic', LITE: 'Voyra CRM Lite', SUITE: 'Voyra Premium' }`;
    `tierOf('SUITE')` PREMIUM, `tierOf('LITE')` LITE, `tierOf('STARTER')`, `tierOf(null)` and `tierOf('CUSTOM')` BASIC.
  - `AgencySubscriptionCard.test.tsx`: replace "edits a custom plan..." with `it('edits a plan: extra seats and annual billing')`
    (from SUITE with 1 extra: change to 3, ANNUAL → `toHaveBeenCalledWith('t1', { plan: 'SUITE', seats: 3, billingCycle: 'ANNUAL' })`,
    Save disabled while the seats field is empty); in "labels seats per plan..." the calls lose `skus`; add
    `it('offers only Basic, Lite and Premium, with no SKU checkboxes')`: Plan options' values are exactly
    `['STARTER', 'LITE', 'SUITE']` and the dialog has no checkbox.

- [ ] **Step 2: Run them.** `rtk npx vitest run lib/plans.test.ts components/platform/AgencySubscriptionCard.test.tsx` → FAIL.

- [ ] **Step 3: Implement.** `EditForm` drops the `skus` state, `toggle`, the SKU fieldset and the Custom option; the
  seat label is `plan === 'SUITE' ? 'Extra seats (5 are included)' : 'Extra seats (3 are included)'`; drop the
  `ALL_SKUS` import if unused.

- [ ] **Step 4: Run step 2 again plus** `rtk npx vitest run app/agency/subscription components/home` and `rtk tsc --noEmit` → PASS / clean.

- [ ] **Step 5: Commit** (frontend): "Remove the Custom plan from the admin subscription card and the plan helpers" + trailers.

### Task 9: Plan & Billing shows the frozen price

**Files:**
- Modify: `lib/api/types.ts` (`AgencySubscriptionResponse.currentPlanPriceMonthly: number`), `app/agency/subscription/page.tsx`
- Test: `app/agency/subscription/page.test.tsx`

**Interfaces:**
- Consumes: `currentPlanPriceMonthly` from `GET /api/agency/subscription` (Task 6).

- [ ] **Step 1: Write the failing tests** (the fixture default gains `currentPlanPriceMonthly: 0`):
  - `it('shows the price the agency is billed on its own plan, and today\'s price on the others')`: plan LITE,
    `currentPlanPriceMonthly: 2499`, `planPrices: { LITE: 2999, SUITE: 4999 }` → the Plan card's "Price" row reads
    "₹2,499 / month + GST"; the CRM Lite card shows ₹2,499, the Premium card ₹4,999.
  - In "shows Voyra Basic with no seats...": the Price row reads "Free".
  - Keep "pays an open upgrade invoice from its plan card" (`Pay ₹5,899 to upgrade`): it is the spec's "the upgrade
    dialog shows the open upgrade invoice's amount".

- [ ] **Step 2: Run them.** `rtk npx vitest run app/agency/subscription/page.test.tsx` → FAIL.

- [ ] **Step 3: Implement.** A `Price` dt/dd pair after "Billing" in the Plan card: STARTER "Free", else
  `formatMoney(sub.currentPlanPriceMonthly, 'INR')` + " / month + GST". In the plan cards,
  `price = current && paid ? sub.currentPlanPriceMonthly : paid ? priceOf(paid) : undefined`.

- [ ] **Step 4: Run step 2 again** → PASS.

- [ ] **Step 5: Commit** (frontend): "Show the agency the monthly price it is billed on Plan and Billing" + trailers.

### Task 10: Settings tabs: General removed, Plans added; frontend gate

**Files:**
- Create: `app/platform/settings/plans/page.tsx`, `app/platform/settings/plans/page.test.tsx`,
  `lib/api/services/platformPlans.ts`, `lib/api/services/platformPlans.test.ts`
- Modify: `components/platform/SettingsTabs.tsx` (+ test), `app/platform/settings/layout.tsx` (+ test),
  `app/platform/settings/page.tsx` (becomes a redirect; + test rewritten), `lib/api/types.ts` (`PlanPrices`)
- Delete: `lib/api/services/platformSettings.ts` and `.test.ts` (no caller left once General goes)

**Interfaces:**
- Produces: `export type PlanPrices = { LITE: number; SUITE: number; STAFF_SEATS: number }`;
  `getPlanPrices(): Promise<PlanPrices>` (`apiGet('/api/platform/plans')`); `setPlanPrices(p: PlanPrices): Promise<PlanPrices>` (`apiPut`).
  Tabs in order: Suppliers `/platform/settings/suppliers`, Pricing rules `/platform/settings/pricing-rules`,
  Plans `/platform/settings/plans` (lucide `Tags`), Admins `/platform/settings/admins`.

- [ ] **Step 1: Write the failing tests.**
  - `platformPlans.test.ts`: GET and PUT hit `/api/platform/plans` with the body as given (pattern of `platformAdmins.test.ts`).
  - `SettingsTabs.test.tsx`: no "General" link; the four links in the order above; Plans has `aria-current="page"` on `/platform/settings/plans`.
  - `layout.test.tsx`: the "General" assertion becomes Plans' href; the subtitle reads "How the platform runs for every
    agency: suppliers, pricing, plans and who can sign in here."
  - `app/platform/settings/page.test.tsx` (replaces the General tests): `expect(() => SettingsRedirect()).toThrow('NEXT_REDIRECT /platform/settings/suppliers')`.
  - `plans/page.test.tsx` (services mocked at the module boundary):
    `it('shows the three prices and the note')` (inputs labelled "Voyra CRM Lite", "Voyra Premium", "Extra seat per
    month" hold 2999, 4999, 399; the note text exactly as in Global Constraints);
    `it('saves the prices and says so')` (change Premium to 5499, Save → `setPlanPrices({ LITE: 2999, SUITE: 5499, STAFF_SEATS: 399 })`, then "Plan prices saved.");
    `it('refuses a price of 0, an empty field or three decimals without calling the API')` (each shows
    "Each price must be more than 0, with at most two decimals." and `setPlanPrices` is not called);
    `it('shows a load error and a save error')` (`ApiError` messages in `role="alert"`).

- [ ] **Step 2: Run them.** `rtk npx vitest run app/platform/settings components/platform/SettingsTabs.test.tsx lib/api/services/platformPlans.test.ts` → FAIL.

- [ ] **Step 3: Implement.** `page.tsx` is `redirect('/platform/settings/suppliers')` like `app/platform/admins/page.tsx`
  (LiveSwitchCard and SettingsCard go with it). The Plans page is a client component: one `Card` titled "Plan prices"
  with the three rows (`Label` + `Input type="number" step="0.01" min="0.01"`, a ₹ adornment and "per month, before
  GST"), the note, and a `Button` Save (`loading` while saving); a value is valid when it is > 0 and has at most two
  decimals (`/^\d+(\.\d{1,2})?$/`).

- [ ] **Step 4: Run the frontend gate:** `rtk npx vitest run --coverage` → all pass, 100 % lines/branches/functions/statements;
  `rtk tsc --noEmit` clean; `rtk npm run lint` at or below the baseline noted before Task 8.

- [ ] **Step 5: Commit** (frontend): "Replace the General settings tab with a Plans tab for plan prices" + trailers.

### Task 11: Playwright

**Files:**
- Create: `e2e/platform-plans.spec.ts`
- Modify: `e2e/subscriptions.spec.ts`, `e2e/platform-supplier.spec.ts` (delete only the test "General has the Go live
  card and the booking rules, and no JSON settings", lines ~109-119), `e2e/shell-and-guards.spec.ts`
  (`PLATFORM_NAV` Settings entry → `'/platform/settings/suppliers'`, since the sidebar link now redirects)

**Interfaces:**
- Consumes: `apiLogin`, `loginAs`, `OWNER`, `PLATFORM_ADMIN`, `API_BASE` from `e2e/fixtures.ts`; the backend of Tasks 1-7
  running on a database that already has V37 and V38 (see Global Constraints; otherwise a scratch copy via `DB_URL`).

- [ ] **Step 1: Edit `subscriptions.spec.ts`.**
  - Every `putPlan` body and `SUITE` lose `skus`.
  - Seat limit (line ~125): `putPlan(A, { plan: 'LITE', seats: 0, billingCycle: 'MONTHLY' })` (3 seats): agents 1-3
    are created, the 4th is refused with a seats message; deactivate agent 1, the 4th is then created; reactivating
    agent 1 is refused (seats); agent 2 still signs in.
  - Public proposal (line ~180): the "without CRM Core" step uses `{ plan: 'STARTER', seats: 0, billingCycle: 'MONTHLY' }`.
  - Read the Plans-tab prices once in `beforeAll` (`GET /api/platform/plans` as admin) and use them instead of the
    literals 4999 (lines ~245, ~328) and 2999 (line ~295).
  - Owner upgrade test: after the plan is LITE, `GET /api/agency/subscription` as the owner has
    `currentPlanPriceMonthly` equal to the LITE price read in `beforeAll`.

- [ ] **Step 2: Write `e2e/platform-plans.spec.ts`** (serial):
  - `test('Settings opens on Suppliers; the tabs are Suppliers, Pricing rules, Plans, Admins')`: `/platform/settings`
    lands on `/platform/settings/suppliers`; the Settings nav's links are exactly those four, in order; no "General".
  - `test('the Plans tab saves new prices and an agency already on a plan keeps its price')`: read `before` from the
    API and the Dummy QA owner's `currentPlanPriceMonthly` (`was`); on the Plans tab set Voyra Premium to
    `before.SUITE + 1`, Save, see "Plan prices saved."; `GET /api/platform/plans` has the new SUITE; the owner's
    `planPrices.SUITE` is the new price and `currentPlanPriceMonthly` still equals `was`; `finally` PUTs `before` back.
  - `test('a price of 0 is refused on screen and by the API')`: Extra seat per month = 0 → the refusal text, nothing
    saved; `PUT /api/platform/plans` with `STAFF_SEATS: 0` → 400.

- [ ] **Step 3: Run** `rtk npx playwright test e2e/subscriptions.spec.ts e2e/platform-plans.spec.ts e2e/platform-supplier.spec.ts e2e/shell-and-guards.spec.ts` → all pass.

- [ ] **Step 4: Commit** (frontend): "Cover the Plans tab and move the subscription journeys off the Custom plan in e2e" + trailers.

### Task 12: Docs

**Files:**
- Modify: `/Users/shivanshsrivastava/Projects/Voyra global/CLAUDE.md` (not in git): the "B2B platform (slice 5)" bullet
  (plans STARTER / LITE / SUITE, CUSTOM gone; `sku_prices` read for LITE, SUITE and STAFF_SEATS only; prices frozen on
  `subscription.plan_price_monthly` / `seat_price_monthly`, V38; billing uses them; `GET/PUT /api/platform/plans`
  NEVER_GATED, audited `PLAN_PRICES_SET`) and the "Agency plans and home" bullet (`CUSTOM` stays admin-only → removed;
  `planPrices` plus `currentPlanPriceMonthly`; upgrades priced at today's Plans-tab prices and frozen at settle)
- Modify: `travel-crm-backend/PROGRESS.md` (a dated "Plan prices and settings (2026-11-01)" section: V38, the rulings
  above, deploy notes below), `travel-crm/CLAUDE.md` (the plans paragraph: Plans tab, no General tab, frozen price on
  Plan & Billing), `/Users/shivanshsrivastava/Projects/Voyra global/.claude/skills/full-regression/scenario-matrix.md`
  (SK4 on Lite with 3 seats; new SK17 Plans tab save and refusal; SK18 an agency on a plan keeps its price after a
  Plans-tab change, an upgrade freezes the invoiced price)

- [ ] **Step 1: Write the doc changes.** Deploy notes to carry into PROGRESS.md: take a backup before V38; V38 logs one
  NOTICE per converted Custom agency (old SKUs and seats, new plan, frozen price), read them in the deploy log; an
  open Custom renewal invoice keeps its lines and settles into the converted plan; V39 (next release) rewrites
  `sku_prices` to the three keys and may drop the column defaults; on a local database, V38 goes on only after V37.
- [ ] **Step 2: Check** `grep -n "CUSTOM\|Custom plan" "/Users/shivanshsrivastava/Projects/Voyra global/CLAUDE.md" travel-crm-backend/PROGRESS.md travel-crm/CLAUDE.md`
  shows only historical lines (marked as replaced) or none.
- [ ] **Step 3: Commit** PROGRESS.md (backend) and `travel-crm/CLAUDE.md` (frontend): "Document plan prices, the Plans tab and the end of the Custom plan" + trailers.
