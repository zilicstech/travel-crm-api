# Platform feedback round (design, 2026-10-30)

Eight points of owner feedback on the platform admin console and the agency booking flow, and the owner's answers
to the open questions (2026-10-30). Plan: `~/.claude/plans/melodic-inventing-pie.md`.

## Owner decisions

- Round trip is a full round trip booked as one booking: one review with both Tripjack price ids, one PNR. It is not
  two one-way bookings.
- Supplier commission terms are per product per supplier (Flight and Hotel each have their own values).
- Suppliers are production only: environment and base URL leave the UI.
- The vendor seeded in every agency keeps its name "Voyra Platform" and becomes a protected system vendor.

Nothing here changes wallet keys, booking states or money arithmetic. The pricing inputs that were platform settings
(Voyra's estimated commission, TDS rate, discount base) move to the supplier, and `PricingMath` is unchanged.

## Phase A — Agency vendors (point 7)

**Backend** (`travel-crm-backend`)
- Tenant migration `V79__vendor_system_flag.sql`: `ALTER TABLE vendor ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;`
  then `UPDATE vendor SET is_system = true, is_active = true WHERE id = (SELECT id FROM vendor WHERE lower(name)='voyra platform' ORDER BY is_active DESC, created_at LIMIT 1);`
  (reactivates it if an owner had switched it off; the mirror needs it active).
- Delete `config/VendorDefaults.java` and `VendorDefaultsTest`; `service/TenantDefaultsSeeder.java` keeps only
  `AgencySettingDefaults`; adjust `config/TenantDefaultsStartupRunner.java` if its "has defaults" check counts vendors.
  Existing tenants keep the vendors they already have (nothing is deleted; bookings reference them by id).
- `entity/Vendor.java` + `VendorResponse`: `isSystem`. `service/VendorService.java` `update`, `updateStatus`
  (and `deactivate`): refuse on a system vendor with `BookingConflictException("SYSTEM_VENDOR", "Voyra Platform is managed by Voyra and can't be edited or deactivated")` → 409.
- Tests: VendorService test for the refusal; TenantDefaultsSeeder test updated.

**Frontend** (`travel-crm`)
- `lib/api/types.ts` VendorResponse `isSystem`. `app/agency/vendors/page.tsx`: system row pinned first, badge
  "Managed by Voyra" (lock icon), no Edit / toggle buttons; everything else unchanged (owner adds vendors,
  activates, deactivates as today).
- e2e: `vendors` spec (if any) + one assertion that the Voyra Platform row has no toggle.

QA seeds (`.claude/run/seed-qa.py`, `seed-claude-qa.py`) still create their own vendors, so
`fixtures.firstVendorId` keeps working on Dummy QA.

## Phase B — Platform Settings hub (point 1) and agency detail tabs (point 5). Frontend only.

**Settings hub**
- `app/platform/layout.tsx` `navSections`: Partners = Applications, Agency Management, Top-up Approvals, B2B Inquiries;
  Platform = Platform Bookings, P&L, **Settings** (`/platform/settings`, icon `Settings2`). Pricing Rules, Supplier,
  Platform Admins leave the sidebar. `getPageTitle` matches `/platform/settings*` → "Settings".
- New `app/platform/settings/layout.tsx`: page header "Settings" + link tabs component
  `components/platform/SettingsTabs.tsx` (same pattern as `components/account/AccountTabs.tsx`: `<nav aria-label="Settings">`,
  `aria-current="page"`): **General** `/platform/settings`, **Suppliers** `/platform/settings/suppliers`,
  **Pricing rules** `/platform/settings/pricing-rules`, **Admins** `/platform/settings/admins`.
- Move pages: `app/platform/pricing-rules/page.tsx` → `settings/pricing-rules/page.tsx`; `app/platform/admins/page.tsx` → `settings/admins/page.tsx`;
  `app/platform/supplier/page.tsx` → split: `LiveSwitchCard` + `SettingsCard` go to `settings/page.tsx` (General),
  supplier credential UI is rewritten in Phase C under `settings/suppliers/page.tsx`.
  Old routes become `redirect()` stubs (`/platform/supplier`, `/platform/pricing-rules`, `/platform/admins`) so bookmarks keep working.
- General tab rows: Go live switch (as today), `voyra_cancel_fee`, `pax_retention_days`. `discount_base`, `tds_rate`,
  `commission_estimate_pct` leave (Phase C moves them to the supplier); `reference_fare_basket*` JSON leaves the UI (defaults stay in DB).
- e2e: `shell-and-guards.spec.ts` `PLATFORM_NAV` (Supplier → Settings `/platform/settings`), `platform-supplier.spec.ts`,
  `agency-pricing.spec.ts` URLs.

**Agency detail** (`app/platform/agencies/[id]/page.tsx`, 829 lines)
- Keep breadcrumb, hero (h1 + status `Badge` directly after it: `platform-agency-admin.spec.ts` uses that XPath), KPI tiles.
  Super Admin Actions become a compact action row in the hero (same button labels: Reveal Credentials, Suspend Agency, …).
- Under the hero, a tab strip (new `components/ui/TabStrip.tsx`, `role="tablist"`/`role="tab"`, blueprint underline style,
  reused from the inline markup in `app/platform/pricing-rules/page.tsx:39-53`) with `?tab=` in the URL (page already uses `useSearchParams`):
  **Overview** (Agency & Tenant Information, Owner Login Credentials, Tenant Isolation Summary),
  **Wallet** (`AgencyWalletCard`), **Plan & Billing** (`AgencySubscriptionCard`), **Pricing** (`AgencyPricingRulesCard`), **KYC**.
- e2e: `agency-pricing.spec.ts:66` opens `/platform/agencies/{id}?tab=pricing`; `platform-agency-admin.spec.ts` unchanged except
  "Reveal Credentials" is on Overview (default tab).

## Phase C — Suppliers (points 2, 3, 4)

**Backend**
- Public migration `V34__platform_suppliers.sql`:
  - `ALTER TABLE platform_supplier_credential ADD COLUMN name VARCHAR(80);` backfill `'Tripjack'`; `ALTER … SET NOT NULL`.
  - New `platform_supplier_product (credential_id FK, product VARCHAR(10) CHECK FLIGHT|HOTEL, commission_estimate_pct NUMERIC(5,2) NOT NULL, tds_rate NUMERIC(5,2) NOT NULL, discount_base VARCHAR(5) NOT NULL CHECK GROSS|BASE, PRIMARY KEY (credential_id, product))`.
    Backfill one row per product for every existing credential from today's `platform_setting` values
    (`commission_estimate_pct` default 5, `tds_rate` 0, `discount_base` GROSS); then delete those three setting rows.
  - `environment` column stays (it drives test-wallet rules, the kill switch and `BookingProviderRegistry.serves`); it is set from the base URL host at save.
- `entity/PlatformSupplierCredential`: `name`; new `entity/PlatformSupplierProduct` (IdClass key, like `AgencyPricingAssignment`) + repository.
- `service/PlatformSupplierCredentialService`:
  - `create(req)` / `update(id, req)` replace `upsert`: request = `{ name, provider, apiKey? (required on create), products: [{product, commissionEstimatePct, tdsRate, discountBase}], isActive }`.
    No `environment`, no `baseUrl`, no `userId` in the request. Base URL = `PROD_BASE_URL.get(provider)` (`https://api.tripjack.com`), environment PROD.
    A UAT row can still only come from the API with an explicit base URL on an internal request field (kept for `.claude/run` scripts and tests; not in the UI) — `ALLOWED_HOSTS` and `requireAllowedHost` stay.
  - Drop the rule "a PROD key needs `tripjack_live_ready`" on save: the owner needs to save and test the key before going live; the live flag still gates every booking (`requireLiveAllowed`) and the startup guard. In-flight-booking refusal stays. Audit `CREDENTIAL_SET` detail adds `name` and the product terms.
  - `list()` → `PlatformSupplierResponse[] { id, name, provider, environment, maskedApiKey, isActive, products[], updatedAt, updatedBy }`; `get(id)`.
  - New `termsFor(PricingProduct)`: active credential of the provider that serves that product (the only provider today is TRIPJACK; mock mode uses the same row, as the global setting did) → its product row; missing → defaults (5 / 0 / GROSS) so nothing breaks before the admin fills it.
- `service/booking/AgencyPriceQuoter.quoterFor` and `service/AgencyPricingService` (`preview`, `worst`) read `termsFor(product)` instead of `settings.discountBase()/commissionEstimatePct()/tdsRate()`; those three methods and keys leave `PlatformSettingService`.
- `controller/PlatformSupplierCredentialController`: `GET /api/platform/supplier-credentials` (list), `POST` (create), `PUT /{id}`, `POST /{id}/test` (replaces `/{provider}/test`; `PlatformSupplierTestService` takes the id, resolves the provider).
- Tests: service and controller tests rewritten; `AgencyPriceQuoterTest`, `AgencyPricingServiceTest`, `FlightSearchServiceTest`, `HotelSearchServiceTest`, `PlatformSettingServiceTest` mocks updated; invariants script unaffected.

**Frontend**
- `lib/api/services/platformSupplier.ts`: `listSuppliers`, `createSupplier`, `updateSupplier`, `testSupplier(id)`; types in `lib/api/types.ts`; `SupplierEnvironment` stays (bookings carry it).
- `app/platform/settings/suppliers/page.tsx`: card list, one card per supplier (name, integration badge, Active badge, masked key,
  per-product chips "Flight · commission 5% · TDS 0% · on gross", last test result), actions **Test connection** (inline OK/Failed badge with ms),
  **Edit**, active toggle. Header button **Add supplier** → `components/platform/SupplierModal.tsx`: Name, Integration (select, Tripjack only for now),
  Products (Flight / Hotel checkboxes; each reveals Commission %, TDS %, Discount base), API key (password; blank keeps the stored key on edit), Active.
- e2e `platform-supplier.spec.ts` rewritten: add/edit through the modal, masked key, test button, no Reveal.

## Phase D — "Book with Voyra" CTA (point 6). Frontend only.

`components/flights/FlightSearchPanel.tsx` card (lines 476-585):
- Fare chips lose their per-chip Button. A chip is selectable (`role="radio"`, click selects; default = cheapest bookable fare); selected chip uses the existing highlight (`border-blue-500 ring-1 ring-blue-200`). Proposal mode keeps its checkboxes beside the radio.
- Card footer row (always shown, not only with `crmCore`): left = selected fare summary ("Saver · ₹X total · you earn ₹Y"); right = `Quick book` (outline, `crmCore` only, unchanged) and **Book with Voyra** (primary, `disabled={!fare.bookable}` with the reason tooltip, `openBook(g, selected)`).
  Label stays "Book with Voyra" (brand, docs, e2e); "Book now" was considered and rejected because the agency must know the wallet pays.
- `components/hotels/HotelDetail.tsx` room option row already has the pair at the bottom; only make Book with Voyra the primary button and Quick book outline (already) — no structural change.
- e2e: `platform-flight-booking.spec.ts:122` (`.first()` still one per card), `booking-lifecycle.spec.ts:282` (one Quick book per article) unchanged; add an assertion that a card has exactly one Book with Voyra button.

## Phase E — Round trip (point 8)

**E0. Spike (free search calls, no booking).** Script `.claude/run/tripjack-probe.py` runs two live `air-search-all` calls with two `routeInfos`
(DEL→BOM→DEL domestic, DEL→DXB→DEL international) using the saved PROD key (decrypted the way the app does, never printed), and saves
redacted responses as `src/test/resources/tripjack/flight-search-roundtrip-domestic.json` and `-combo.json`. Confirms: `tripInfos.ONWARD` + `RETURN`
for domestic, `tripInfos.COMBO` for international, how return segments are marked (`isRs` / `sN`), and that `review` accepts `priceIds: [onward, return]`
(the review call is also free; it is not booked). The builder adjusts the shapes below to what the fixtures show.

**Backend**
- `dto/FlightSearchQuery.returnDate` validated on or after `departDate` (`@AssertTrue`).
- `dto/FlightOfferResponse` and `FlightGroupResponse` gain `direction` (`ONWARD | RETURN | COMBO`) and `legs[]` (`FlightLegResponse`: airline, airlineCode,
  flightNumber, origin, destination, departDate, departTime, arriveDate, arriveTime, duration, stops). One-way and split offers have one leg; COMBO has two.
  Existing top-level fields keep describing the first leg (nothing downstream breaks).
- `service/tripjack/TripjackFlightSearchProvider`: `buildRequestBody` adds the second route when `returnDate` is set; `toOffers` reads `ONWARD`, `RETURN` and `COMBO`,
  splitting COMBO segments into legs by the return marker from the spike. Unit tests on the new fixtures.
- `service/MockFlightSearchProvider`: with `returnDate`, also emits RETURN offers (destination→origin on `returnDate`; ids `mock-…-R`, `adultFare` accepts the suffix).
  COMBO is covered only by the Tripjack fixture tests (mock stays split), noted in the design doc.
- `service/FlightResultPaging` / `FlightSearchService.searchPage`: group by key + direction; `FlightSearchPageResponse` gains `tripType` (`ONE_WAY | ROUND_TRIP_SPLIT | ROUND_TRIP_COMBO`)
  and `returnFlights` (RETURN groups, same filters and sort, not paged — shortcut, upgrade if a busy route makes the payload heavy). `flights` holds ONWARD or COMBO groups, paged as today.
- Review: `dto/FlightReviewRequest` gains `returnOfferId` (optional) and `returnLeg` display hints (mock only). `service/booking/FlightBookingProvider.review(List<String> offerIds, …)`;
  `TripjackFlightBookingProvider` sends `priceIds` = both; `MockFlightBookingProvider` sums both fares and builds two `sI` segments.
  `PlatformBookingService.review`: `searchDisplayedFare` is the sum the agency saw; travel date = first departure (as now); pax mix unchanged.
  Book, poll, cancel, mirror unchanged (`VoucherTrip.details` already reads out-and-back legs as a Round Trip).
- Tests: provider tests (fixtures), paging test, `PlatformBookingServiceTest` round-trip review, `MockFlightBookingProviderTest` two segments.

**Frontend**
- `components/home/HomeSearchCard.tsx`: One way / Round trip pills above the flight row; `FlightQuery.returnDate?`; Return `DateCell` (min = departure) when round trip;
  `flightSearchUrl` adds `ret=`; recent chip reads "DEL ⇄ BOM". `lib/flightSearchParams.ts` reads `ret`.
- `components/flights/FlightSearchPanel.tsx`: trip type pills + Return date in the form; `runSearch` sends `returnDate`; header copy drops "One-way search".
  Results by `tripType`:
  - ONE_WAY: as Phase D.
  - ROUND_TRIP_SPLIT: two columns, Onward and Return, each a list of the same cards with selectable fares (default cheapest bookable each); sort and filters apply to both;
    pagination applies to Onward, Return column has a client-side "Show more". Page-level sticky bar (reuse the proposal bar markup at lines 602-617):
    "DEL→BOM 6E-203 ₹X · BOM→DEL UK-… ₹Y · Total ₹Z · you earn ₹W" + **Book with Voyra**. Quick book hidden in round trip (the CRM quick book is one flight).
  - ROUND_TRIP_COMBO: one list; each card header shows both legs stacked; footer as one-way.
- `components/booking/BookWithVoyraModal.tsx`: `FlightBookTarget` gains `returnGroup?`/`returnFare?`; review sends `returnOfferId` + return hints; summary lists both legs; the displayed fare is the sum.
- `components/lead/FlightSearchModal.tsx`: prefill return date from `sectors[1]` when the service is a Round Trip; Add to Proposal keeps one line per picked fare.
- e2e: `agency-home.spec.ts` toggle + URL `ret=`; new `platform-flight-roundtrip.spec.ts` on the mock: search DEL↔BOM, pick onward and return, book, assert the booking itinerary has two legs and the CRM mirror says Round Trip.

## Calls made (owner can override)
1. Supplier save no longer requires Go live; bookings still do.
2. Return list is not paged (filters and sort apply).
3. Mock round trip is always split (no mock COMBO).
4. Quick book hidden in round-trip results.
5. Label stays "Book with Voyra".
6. Legal identity, SKU prices and founding agencies get no settings UI in this round.

## Changes after the adversarial review (2026-10-30)

The review found these problems in the first draft. Each line says how the design now handles it.

1. **Editing supplier terms.** There is now a separate `PUT /api/platform/supplier-credentials/{id}/products`. It changes only the terms, never refuses because bookings are in flight, and never touches the environment or base URL. `update` keeps the stored environment and base URL unless a new key is entered. Only `create` defaults to PROD and the production host. Range checks: pct between 0 and 100.
2. **What moves to the supplier.** Only `commission_estimate_pct` moves, per supplier per product. `tds_rate` and `discount_base` are Voyra's own rules and stay on the General settings tab. (Builder's call; the owner can override.)
3. **Rolling deploy.** V34 copies `commission_estimate_pct` into each product row and deletes no settings rows, so an old instance still prices correctly while the deploy rolls. A later migration can drop the unused row.
4. **Multiple suppliers.** There is one row per integration. Create is refused with 409 `SUPPLIER_EXISTS` when that integration already has a row, and the Add supplier modal lists only integrations that have none yet. Activation stays per row. Test connection builds its client from the row being tested, not from the active one, and the audit records the credential id.
5. **e2e and the live key.** The supplier e2e never saves an active fake key over the live row. It stubs create with `page.route`, and changes terms through the terms-only endpoint, putting the values back afterwards.
6. **PNRs on a split round trip.** Every sector PNR is kept. The booking's PNR becomes the distinct sector PNRs joined with " / ", so both reach the CRM mirror.
7. **Special-return fares.** The probe records Tripjack's pairing markers. When they exist, the Return column shows only fares that can pair with the chosen onward fare. A pairing refusal gets its own fixed message.
8. **The probe.** It runs locally, against the local DB and the local encryption key, and never prints or writes the key. If Tripjack refuses this machine's IP, the fixtures are built from Tripjack's documented shapes and marked "to be corrected by slice 0", like the rest of the Tripjack providers.
9. **Finding the system vendor.** V79 inserts Voyra Platform when no row matches its name (as V78 does), flags it `is_system`, and adds a partial unique index on `is_system`. The mirror finds the vendor by `is_system`, not by name.
10. **Editing the system vendor.** Name, service types and active status are locked. Tax and bank fields stay editable, so the supplier bills the mirror posts can carry Voyra's GSTIN and state.
11. **e2e vendor pick.** `fixtures.firstVendorId` and the pickers used by the specs skip `isSystem`.
12. **Rounding and the mock id.** "Price changed" ignores a difference of 0.02 or less, so rounding the two legs separately does not trigger it. The mock review id is built from the onward offer id only.
