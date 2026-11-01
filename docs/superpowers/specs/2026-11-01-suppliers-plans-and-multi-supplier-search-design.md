# Suppliers in the database, multi-supplier search, and plan prices

Date: 2026-11-01. Owner decisions from the client discussion of the same day; reviewed by an adversarial critique, an edge-case hunt and a builder's check, whose findings are folded in. Repos: `travel-crm-backend` (Spring Boot 3.3, schema-per-tenant) and `travel-crm` (Next.js 15). Everything here is platform-level (public schema); no tenant migration.

## Intent

Three changes the owner wants shipped together, as fast as possible:

1. **Suppliers live in the database.** A supplier is a name, a base URL, an API key, and for each product it serves (Flight, Hotel) a search toggle and a booking toggle. The platform admin turns search and booking on and off from the Suppliers tab. No environment variable chooses a provider any more, there is no "go live" switch, and Voyra no longer tracks supplier commission or its own profit.
2. **Every enabled supplier is searched.** Flight and hotel results show the prices of every supplier with search on, one row per flight across suppliers, with the cheapest supplier on the row and a "Book on" card per supplier when the row is expanded. The agent books on the supplier they pick, by its real name (Tripjack). Tripjack is the only integration today; a new integration needs only its provider class and a supplier row.
3. **Settings.** The General tab goes. A Plans tab sets one monthly price per plan (Lite, Premium) and the extra-seat price. The Custom plan goes. An agency keeps the price it signed up at.

### Goals
- A platform admin can add a supplier row for an integration the code has (a second account, or a new integration once its class is deployed), switch it on for search, watch its prices appear beside Tripjack's, then switch booking on, with no deploy for the switch.
- An agent sees the market: the same flight from every supplier, and chooses.
- Plan prices are set on screen and never move the bill of an agency already on a plan.

### Non-goals
- Supplier-side commission, Voyra's earnings, P&L and statement reconciliation: removed from the product for now (owner: "no need to track loss or profit for now"). Their columns and tables stay, so history is kept and nothing is dropped in this release.
- Matching one hotel across suppliers (see Part 2, owner question 2).
- A per-agency price negotiation screen: the frozen price is set by the system.
- Changing how pricing rules (discount and commission to the agency) work. The one consequence of removing the supplier figure is noted under "Commission cap".

### Anti-goals
- A booking that goes to a different supplier than the one the agent picked, or whose two legs go to two suppliers.
- A search that fails, or shows "no flights", because one supplier is down.
- A renewal invoice that changes because the platform edited a plan price, or because a migration converted the plan.
- Real wallet money reaching the mock, or a UAT review confirmed against PROD.

## Part 1: Suppliers in the database

### Data: migration V37 (expand only)
A rolling deploy runs two instances, so V37 (suppliers) and V38 (subscriptions, Part 3) add and backfill but drop nothing; V39, shipped with the next release, drops what this release stopped reading (`is_active`, `commission_estimate_pct`, `allow_above_earned`, the retired setting rows). The entities stop mapping those columns now; every one of them is nullable or has a default, so the new code can insert without them.

- `platform_supplier_credential`
  - drop `UNIQUE(provider)` and the partial index `uq_platform_supplier_credential_active`; add `UNIQUE (lower(name))`. Several rows per integration are allowed (a second account; a second mock row for QA).
  - `base_url` becomes editable within the row's environment (see Guards).
  - insert a row `name = 'Mock supplier', provider = 'MOCK', environment = 'MOCK', base_url = 'mock://local'`, key encrypted from a fixed string, both products, every toggle off; set `credential_id` to it on every `platform_booking` with `environment = 'MOCK'` and `credential_id IS NULL`. (Migration numbering: V37 is this part, V38 is Part 3, so the two streams never share a file.) Rows from before this change then resolve their supplier like any other.
- `platform_supplier_product`
  - add `search_enabled BOOLEAN NOT NULL DEFAULT false`, `booking_enabled BOOLEAN NOT NULL DEFAULT false`. Toggles are per supplier and product (owner question 3): Tripjack flights can take bookings while Tripjack hotels, whose integration is unverified, stay search-only.
  - backfill: every toggle **off**. SQL cannot see which provider each deployment ran from its environment variables, so after deploy the admin switches on what that environment searched and booked before (deploy note 2). Locally the seed script does it.
- `pricing_rule.allow_above_earned` stops being read (dropped in V39).
- `platform_setting`: the keys `tripjack_live_ready`, `commission_estimate_pct`, `discount_base`, `reference_fare_basket`, `reference_fare_basket_hotel` stop being read (rows deleted in V39). `tds_rate` stays (see TDS). `platform_setting_request` (V28) stays as history.
- `subscription`: migration V38, see Part 3.
- V37 refuses to run (RAISE EXCEPTION, as V34 does) when a database would change money silently: a COMMISSION rule with `allow_above_earned = false` and `pct` above its product's `commission_estimate_pct` (the local database has none), or `discount_base <> 'GROSS'`. The admin fixes the data first.
- `SupplierProvider` gains `MOCK`. `SupplierEnvironment` keeps `MOCK`, `UAT`, `PROD`.

### Supplier record (API `/api/platform/supplier-credentials`, SUPER_ADMIN)
- `POST` creates: `name` (≤ 80, unique ignoring case), `provider` (`TRIPJACK` | `MOCK`), `baseUrl`, `apiKey`, `products`: a non-empty list of `{product, searchEnabled, bookingEnabled}`.
- `PUT /{id}` updates `name`, `baseUrl`, `apiKey` (blank keeps the stored key) and `products` (replaces the set). `provider` never changes; a different one is 400. (`PUT /{id}/products` goes.)
- `POST /{id}/test` runs the provider's test search for that row (the MOCK provider answers at once).
- No delete. A supplier with every toggle off is dormant.
- Response: `id, name, provider, environment, baseUrl, maskedApiKey, products[{product, searchEnabled, bookingEnabled}], updatedAt, updatedBy`. `environment` is derived and fixed at create.
- Guards:
  - `baseUrl` must be `https://`, no port, query or user info, path empty or `/`; the host is lower-cased and a trailing dot stripped, and must be on the provider's allow-list, which decides the environment (UAT: `apitest.tripjack.com`; PROD: `api.tripjack.com`). Tripjack's hotel host is derived from the environment as today (`apitest-hms` / `hms-search`): one base URL per supplier, the integration knows its sibling hosts. The MOCK provider takes `mock://local` only and is environment MOCK.
  - The environment never changes after create: a `baseUrl` whose host is on the other list is 400 "Add a new supplier for the production account". So a booking's stamped environment always matches its supplier's.
  - `apiKey` rotation is always allowed. `bookingEnabled = false` and `searchEnabled = false` are always allowed: turning booking off is the kill switch and must work in an incident; rows already in flight keep being served (Provider wiring).
  - Audit: `CREDENTIAL_SET` on create and update with the changed field names (never the key), `CREDENTIAL_TEST` as today.
- Creating a MOCK row is refused (400 `PROVIDER_UNAVAILABLE`) when the backend runs the `cloudrun` profile. The MOCK beans exist in every profile, so a MOCK row that already exists is still served. The seed script and the e2e fixtures refuse to run unless the API they call is on localhost.

### Provider wiring
- Removed: the properties `app.flight-supplier.provider`, `app.hotel-supplier.provider`, `app.flight-booking.provider`, `app.hotel-booking.provider` and their env variables; `@ConditionalOnProperty` on the eight provider beans; `SupplierStartupLogger`'s provider lines; `TripjackLiveSwitchGuard`; `requireLiveAllowed` and 409 `LIVE_BOOKINGS_PAUSED`; `resolveActive`.
- Every provider bean always exists and is registered under its `SupplierProvider`. A provider call takes the supplier row (`ResolvedCredential`: base URL, key, environment) as a parameter, as the Tripjack flight search's test overload already does. The mock behaviour switches (`app.flight-booking.mock.*`, `app.hotel-booking.mock.*`) stay as properties: they are test knobs, not provider selection.
- `BookingProviderRegistry` becomes `SupplierRegistry`: `searchable(product)` returns the supplier rows with `search_enabled` for that product whose provider bean exists (a row whose bean is missing is skipped with one WARN per startup); `bookable(supplierId, product)` checks `booking_enabled`; `supplierFor(booking)` resolves `platform_booking.credential_id` to its row and provider.
- `serves(booking)` is true when the row's supplier exists, its provider bean exists, and the supplier's environment equals the booking's. `SUPPLIER_MISMATCH` keeps its meaning for the rest.
- Provider checks that used `environment != MOCK` to mean "Tripjack's cancel is asynchronous" (`PlatformBookingService.cancel`, the poller's CANCELLING skip) become a capability on the provider, `BookingSupplier.cancelIsAsync()` (Tripjack true, mock false). `environment` keeps only the wallet, watermark and admin-display rules.
- A booking already in flight keeps being polled, cancelled and resolved through its supplier after `booking_enabled` is turned off; the toggle gates review and confirm only. At confirm, a REVIEWING row whose supplier row was updated after the review was stamped (`updated_at` after the row's `created_at`) is refused as an expired review (the existing 409), so a key or URL edit never confirms a stale review.

### Environment of a booking
`platform_booking.environment` and `credential_id` are stamped at review from the chosen supplier. The test-wallet rule is unchanged: a PROD row needs a live wallet, a UAT or MOCK row a test wallet. The e-ticket's TEST mark and the admin response keep reading `environment`.

### Commission and profit tracking removed
- `AgencyPriceQuoter`: displayed fare and the agency's commission come from the pricing rules alone. `discount_base` is fixed to GROSS (V37 refuses otherwise). `PriceQuote` loses `earned` and `net`; `pricing_snapshot` stops writing `voyraEarned`, `voyraNet`; the `voyra_earned*`, `voyra_net` columns are written null.
- **Commission cap.** Today a commission rule without "allow above earned" pays `min(rule pct, supplier estimate)` of base. The cap goes with the supplier figure, so a rule pays exactly its percentage. V37 refuses to run while any capped rule exceeds its estimate, so no agency's earnings move at deploy without the admin editing the rule first.
- **TDS stays.** The agency's commission is paid net of TDS through the wallet (`pb-tds`), a statutory posting, not profit tracking. `tds_rate` keeps its default 0 and is no longer on screen.
- Removed: `PlatformPnlService`, `PlatformPnlController` (`/api/platform/pnl`), `TripjackStatementService`, `TripjackStatementConfig`, `PlatformStatementController` (`/api/platform/statements`), `AgencyPricingService.preview/impact/impactOf/worst` and the loss-making audit detail, `GET /api/platform/pricing-rules/{id}/impact`, `GET /api/platform/agencies/{id}/pricing/preview`. The statement tables stay. `ControllerSkuTable` and its contract test drop the two controllers and add the Plans controller (NEVER_GATED).
- Platform dashboard: `voyraNet30d` goes; `gmv30d` stays. Admin booking response and table: `voyraEarned`, `voyraEarnedActual`, `voyraNet` and the "Voyra net" column go; `agencyEarns` and the discount stay. Cancel adjustments and fare variance stop touching the voyra figures; wallet postings are unchanged.
- The subscription PDF renderer keeps rendering old CUSTOM invoice lines by their stored labels.

## Part 2: Multi-supplier search and booking

### Search fan-out
- `FlightSearchService.search(query)` asks every `searchable(FLIGHT)` supplier in parallel on a bounded executor: `CompletableFuture.supplyAsync` per supplier, `allOf(...).orTimeout(overall deadline, 40 s)`; a supplier that throws, is rejected by the executor or is still running at the deadline goes into `suppliersFailed` (name only; the error class is logged). Tasks receive the supplier row and the query explicitly and copy the MDC; provider code never reads `TenantContext` or touches JPA.
- No supplier searchable for the product → 409 `NO_SUPPLIER` ("No supplier is enabled for flight search"). Every searched supplier failed → 503 `SUPPLIERS_UNAVAILABLE` ("Suppliers are not responding; try again in a minute"), never an empty page. A partial result carries `suppliersFailed: ["Tripjack"]` and the UI says "Tripjack did not respond" above the results.
- Caching: a page with `suppliersFailed` non-empty is not cached. The cache key adds the set of searchable supplier ids with their `updated_at`, so a toggle change invalidates. `bookable` per supplier ("Not taking bookings") is computed at page time from the live rows, not stored in the cache.
- Every offer carries `supplierId` and `supplierName`. Supplier-scoped ids are namespaced `<supplierId>:<provider id>`: `offerId`, `returnOfferId`, `specialReturnId`, `pairsWith`, hotel `hotelId`, `optionId`, `correlationId`. The review parses the namespace; an id with no namespace or an unknown supplier is 400 "This fare is no longer available; search again".
- Grouping (`FlightResultPaging`): the key is canonical, `airlineCode | digits of flightNumber | ISO depart date-time | ISO arrive date-time` per leg plus direction, and each provider emits canonical values (a unit test feeds two providers' shapes). One helper derives `cheapestPrice`, `cheapestSupplierName` and `supplierCount` from a group's fares, ordered by displayed fare with bookable fares first, and is used by `group()` and by every filtered copy (refundable-only, airlines, the new Suppliers filter). Fare chips keep `supplierName`.
- Round trips: a supplier's own answer stays consistent. The page is `ROUND_TRIP_COMBO` only when every supplier answered COMBO; otherwise it is `ROUND_TRIP_SPLIT`, and a supplier's COMBO offers appear as combined rows (both legs stacked, their own Book button, no return pick). Return fares are filtered to the chosen onward fare's supplier; when that supplier has no return fares the column says "No return flights on Tripjack; pick an onward fare on another supplier". The server refuses a review whose `offerId` and `returnOfferId` are on different suppliers (400 "Both legs must be on one supplier"). (Owner question 1.)
- Hotels: `HotelSearchService.search` fans out the same way. **Hotels are not merged across suppliers**: each supplier's hotel is its own row with the supplier name on it, and the detail page shows that supplier's options (owner question 2). Hotel review refuses a `hotelId` and `optionId` on different suppliers.

### Review and booking
- `PlatformBookingService.review` resolves the supplier from the offer id, requires `bookable(supplierId, product)` (else 409 `SUPPLIER_NOT_BOOKABLE`, "Tripjack is not taking bookings right now; pick another supplier"), stamps `credential_id` and `environment` from it, and calls that supplier's booking provider. Confirm, poll, cancel quote, cancel and admin resolve use `supplierFor(booking)`.
- `supplier_review_id` uniqueness is unchanged. The agency and admin booking responses gain `supplierName`.
- The CRM mirror, e-ticket, wallet keys, state machine and invariants do not change.

### Frontend
- Flight results: a row shows the cheapest bookable displayed fare and its supplier name, plus "+N more" when other suppliers have the flight. Expanding the row shows a **Book on** card per supplier: supplier name, that supplier's fare chips (fare type, displayed fare, "You earn"), Book with Voyra on the selected chip. A supplier not taking bookings shows its card greyed with "Not taking bookings". Quick book (CRM) is unchanged. Sort and filters work on the row's cheapest bookable fare; a Suppliers filter lists the suppliers in the results.
- Round trip split: picking an onward fare limits the return column to the same supplier, with the line "Return flights on Tripjack (same supplier as your onward)".
- Hotels: the supplier name on each row and on the detail page.
- `BookWithVoyraModal` shows "Booking on Tripjack" in the review step; `supplierName` prints on the Voyra card and the tracker.
- Platform › Settings › Suppliers: a card per supplier with name, environment badge, masked key, last test result, and a row per product with its two toggles (switching one is an inline PUT). "Add supplier" and "Edit" open the modal: name, base URL, API key, and a product list (Flight, Hotel) each with search and booking switches. The commission modal goes.
- Platform › Pricing rules: no impact figures, no "may exceed earned" badge; the commission rule modal loses "allow above earned". Agency pricing card: no preview, no loss-making badge.
- Platform nav: P&L entry and page removed; Statement import removed; dashboard loses the Voyra net tile.

## Part 3: Settings and plans

### General tab removed
`/platform/settings` redirects to `/platform/settings/suppliers`. Tabs: Suppliers, Pricing rules, Plans, Admins. The settings endpoint stays for `voyra_cancel_fee`, `pax_retention_days`, `tds_rate`, `sku_prices`, the Voyra legal identity and `subscription_sac`, with no screen. `LiveSwitchCard` and `SettingsCard` go.

### Plans tab
- `GET /api/platform/plans` → `{ LITE: 2999, SUITE: 4999, STAFF_SEATS: 399 }` (monthly, INR, ex-GST). `PUT /api/platform/plans` with the same shape; each > 0, two decimals; audited `PLAN_PRICES_SET` with old and new values. New `PlatformPlanController`, NEVER_GATED.
- Stored in the existing `sku_prices` setting; the service reads and validates only `LITE`, `SUITE`, `STAFF_SEATS` and ignores other keys (V39 rewrites the stored JSON).
- Screen: three rows (Voyra CRM Lite, Voyra Premium, Extra seat per month), each a rupee input, Save, and the note "Annual billing is 10 months. Changing a price affects new subscriptions and upgrades only; agencies already on a plan keep their price."
- `GET /api/agency/subscription` keeps `planPrices` (LITE, SUITE, for the upgrade dialog) and adds `currentPlanPriceMonthly` (the frozen price) so the agency's own plan card shows what it is billed. The upgrade dialog shows the open upgrade invoice's amount when one exists.

### Custom plan removed
- `SubscriptionPlan` becomes `STARTER`, `LITE`, `SUITE`. V38 converts every CUSTOM subscription: SUITE when its skus include `ACCOUNTS_GST`, else LITE; `seats = CASE WHEN 'STAFF_SEATS' = ANY(skus) THEN GREATEST(seats - included, 0) ELSE 0 END`; `skus` rewritten to the plan's fixed list; the plan CHECK constraint rewritten. The migration logs each converted agency with its old and new plan. An open ISSUED invoice keeps its CUSTOM lines and settles into the converted plan when paid. (Local database: one CUSTOM row becomes LITE.)
- `SubscriptionUpdateRequest` loses `skus`; `EntitlementService` and `SubscriptionBillingService.lines` lose their CUSTOM branches; the admin subscription card loses the Custom option and the SKU checkboxes; `tierOf` loses the CUSTOM case.

### Frozen price
- `subscription` gains `plan_price_monthly NUMERIC(12,2) NOT NULL` and `seat_price_monthly NUMERIC(12,2) NOT NULL`. V38 backfills them with what the agency pays today: the plan price for LITE and SUITE, the sum of its SKU prices for a converted CUSTOM row (its renewal does not change), 0 for STARTER; the seat price from `sku_prices.STAFF_SEATS`.
- They are set whenever the plan changes, from the Plans-tab prices at that moment: admin `PUT .../subscription` with a different plan, and `extendTrial` (which sets SUITE). A paid upgrade invoice freezes the unit amounts of its own lines at `settle`, never the setting, so the agency renews at the price it paid. An admin change of seats or cycle alone keeps the frozen prices.
- `SubscriptionBillingService.lines` bills `plan_price_monthly × months` plus `extra seats × seat_price_monthly × months`; `raiseUpgrade` prices the invoice at the current Plans-tab prices and names the plan. Waived and complimentary subscriptions carry prices too; they are never billed.

## Cross-cutting

### Security and privacy
- API keys stay encrypted, write-only, masked to the last 4 characters; never in audit details or logs.
- Agencies see supplier names and opaque namespaced ids, never Voyra figures (the leak tests stay).
- The `baseUrl` allow-list is the one outbound-call guard; it stays per provider and fixes the environment.

### Tests and gates
- Backend `mvn verify` at 100 % coverage and the blueprint audit; the Testcontainers ITs.
- Invariants: the `voyra_net` formula rules are removed; new rule "every platform booking past REVIEWING has a `credential_id` that exists".
- Frontend Vitest at 100 %, tsc, lint baseline, Playwright:
  - `platform-supplier.spec.ts` rewritten for per-product toggles, immutable provider and environment, key rotation.
  - fixtures: an idempotent "ensure Mock supplier" (list, then create or enable) replaces the env assumption; the other suppliers' search is switched off for the run; `ensureRule` no longer passes `allowAboveEarned`.
  - `subscriptions.spec.ts` CUSTOM cases move to LITE and SUITE; `agency-pricing.spec.ts` drops the impact assertions.
  - A second mock row in the fixtures (`Mock supplier 2`, same provider) exercises the multi-supplier page end to end: "+N more", "Book on" cards, a booking on the second mock, a round trip limited to one supplier. Mock results are deterministic, so both rows return the same flights and group into one row.
- Matrix rows and `CLAUDE.md` sections updated: the slice 3, 5 and 6 bullets that mention the env properties, the live switch, P&L, statements and Custom.

### Deploy notes
1. V37 and V38 are expand-only; V39 (next release) drops the retired columns and setting rows. Take a backup before each.
2. After V37 every supplier toggle is **off**. Before switching traffic, read each environment's `FLIGHT_SUPPLIER`, `HOTEL_SUPPLIER`, `FLIGHT_BOOKING_PROVIDER`, `HOTEL_BOOKING_PROVIDER` and `tripjack_live_ready`, then set the Tripjack row's product toggles to match through the Suppliers tab, and remove the four variables from the Cloud Run service (they are ignored). Production today runs PROD Tripjack search for flights with live bookings paused: set Flight search on, Flight booking off until you want it on; leave Hotel off (its Tripjack integration is unverified).
3. Count PROD rows in HELD, BOOKING, UNKNOWN or CANCELLING before the deploy; they keep settling through their supplier, but confirm the Tripjack row's environment is PROD first.
4. V37 refuses on a capped commission rule above its estimate or a `discount_base` of BASE; fix the data and rerun.

## Calls made by the author (owner can override)
1. One base URL per supplier; Tripjack's hotel host is derived from the environment of that URL. The environment is fixed at create; a production account is a new supplier row.
2. Several rows per integration are allowed; the provider is fixed at create.
3. The mock is a supplier row. Its beans run everywhere (existing mock rows keep settling); creating a mock row is refused on the `cloudrun` profile; a mock booking still needs a test wallet.
4. TDS on agency commission stays, hidden, at its default 0.
5. `discount_base` fixed to GROSS; V37 refuses otherwise.
6. P&L, statement and setting-request tables stay; screens and endpoints go.
7. Supplier-scoped ids are namespaced `<supplierId>:<id>`.
8. V37 refuses while a capped commission rule exceeds its estimate, so no earnings move at deploy.
9. Expand-only V37 and V38; drops in V39.
10. Seat price frozen with the plan price; upgrades and admin plan changes take the current prices; a paid upgrade freezes what the invoice charged.
11. CUSTOM rows convert by ACCOUNTS_GST and keep today's bill.
12. Every toggle backfilled off; the admin switches on per environment after deploy.
13. A second mock supplier row is the end-to-end test of the multi-supplier page.

## Owner questions

Approved by the owner on 2026-11-01 with the recommendations: one supplier per round trip, no hotel merging, toggles per product.

1. Round trip: both legs on the supplier of the onward fare, chosen once. Confirm.
2. Hotels: shown one row per supplier's hotel with the supplier name, no merging (recommended for now; the same hotel from two suppliers is two rows), or merged by name and city, which can merge two different hotels or split one?
3. Toggles per product (recommended: Tripjack flights can take bookings while Tripjack hotels stay search-only), or the two toggles per supplier as described in the discussion?
