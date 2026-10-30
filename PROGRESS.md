# Voyra Global Travel CRM — Backend Progress

Status as of **2026-08-13**. This document explains what has been built, how it was verified, and what's left. The architecture decisions behind all of this are recorded in [`docs/BACKEND_PLAN.md`](docs/BACKEND_PLAN.md) — the approved plan this implementation follows.

## TL;DR

All 15 original build tasks are complete, plus a full remediation pass (see
[`docs/REMEDIATION_PLAN.md`](docs/REMEDIATION_PLAN.md)) that closed every gap between this
codebase and [`BACKEND_BLUEPRINT.md`](../BACKEND_BLUEPRINT.md). The backend runs locally, is
fully wired to a real PostgreSQL database, and is backed by a **63-test suite** (unit + service
+ controller slice tests, run via `mvn verify`) that converts every correctness claim in this
document into an executable assertion — including the two hardest correctness/security cases:
multi-tenant data isolation and the public proposal link's pricing-safety guarantee.
**68 endpoints** are live across auth, platform, agency, and public surfaces.

Blueprint compliance is now machine-verified: `scripts/blueprint-audit.sh` runs in the `verify`
Maven phase and currently reports **COMPLIANT** against all 14 mechanical checks.

What's *not* done: no GitHub remote yet (repo is `git init`'d locally only, waiting on your access), the existing `travel-crm-main` frontend is not yet wired to call this backend, two integration tests (tenant isolation, proposal-link resolution) are written but unverified because Docker isn't installed on this machine, and a few things were explicitly scoped out per your earlier decisions (audit log, notifications, flight/hotel search, GCS storage).

## Where everything lives

| What | Where |
|---|---|
| Backend source | `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend` |
| This progress doc | `travel-crm-backend/PROGRESS.md` (this file) |
| The approved architecture plan | `travel-crm-backend/docs/BACKEND_PLAN.md` (copy of the original plan file, kept in the repo so it survives independent of the Claude Code session) |
| Original plan file (Claude Code tool storage) | `/Users/shivanshsrivastava/.claude/plans/eager-wibbling-haven.md` |
| Architecture blueprint this follows | `/Users/shivanshsrivastava/Projects/Voyra global/BACKEND_BLUEPRINT.md` |
| BRD | `/Users/shivanshsrivastava/Downloads/Voyra_Global_Pvt_Ltd_CRM_BRD.pdf` |
| Frontend (not yet wired to this backend) | `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-main` |

## Codebase size

181 main-source Java files plus 24 test files (99 test methods). 16 entities, 16 repositories, 20 services, 13 controllers, 64 DTOs, 18 enums, 3 Flyway migrations (2 public-schema, 1 tenant-schema).

## Architecture, in brief

- **Multi-tenant**: one Postgres schema per Agency (`tenant_<id>`), routed by `search_path`, exactly per the blueprint's pattern. Confirmed with two real agencies during testing — an owner token from one agency gets a structural 403 trying to touch another agency's data, never a silent leak.
- **Auth**: stateless JWT, three roles (`SUPER_ADMIN`, `AGENCY_OWNER`, `AGENT`), reversible AES-256-GCM password storage (chosen specifically because Owners must be able to retrieve a generated Agent password, and Platform Admins an Agency Owner's — the one case the blueprint sanctions this for).
- **Client / Member / Lead party model**: the tenant schema is a three-level spine — `client` (the commercial entity, B2C household or B2B group) → `member` (a person, including the client's own primary member) → `lead_members` (the per-lead traveller manifest, a many-to-many). This replaced the flat `customer`/`family_member` pair, which could say *how many* people travelled but never *who*: `family_member` held only name, relation and date of birth, and was referenced by no lead, booking, visa or invoice. See "Tenant schema rebuild" below.
- **Denormalization**: every list-heavy row (bookings, invoices, visas, leads) carries a live-synced `*_name` snapshot column instead of requiring a join, and multi-value fields (lead categories, lead kid ages) are native Postgres arrays instead of join tables — both by design, to eliminate N+1 risk structurally rather than patch it later.
- **Derived, never stored**: airline pax type comes from date of birth measured against the *travel* date (`util/PaxTypeCalculator`), so a child who turns 12 between enquiry and departure prices correctly. `member` deliberately has no age column for exactly this reason.
- **Money & business rules are server-side, never client-trusted**: booking profit, proposal margin %, GST, and invoice payment status are always computed in the service layer from the current data, regardless of what a client sends.
- **Public proposal link**: the one deliberately unauthenticated surface. Resolves a high-entropy token (not the lead's real ID) through a public-schema index, reads the correct tenant schema via a `REQUIRES_NEW` cross-tenant transaction, and returns a hand-built DTO that structurally cannot leak `netCost`, margin, or any other internal field — verified by asserting those keys are absent from the raw JSON, not just hidden by a UI.

## What's built, module by module

### Foundation (tasks 1–8)
Local dev environment (Java 17, Maven, Postgres 16 — all installed and verified on this machine), Maven project skeleton, public + tenant Flyway migrations, full tenant-context/search_path infrastructure, JWT security core, global exception handling (`ApiErrorResponse` envelope, consistent status-code mapping), in-memory caches with startup loaders, idempotent demo-data seeding, and a local file-storage abstraction (swappable to GCS later without touching callers).

### Agency + Agent management
- `POST/GET /api/platform/agencies`, `GET/PATCH /api/platform/agencies/{id}`, `GET /api/platform/agencies/{id}/credentials` — Platform Admin onboards an Agency; this *is* tenant provisioning (creates the schema, runs its migrations).
- `POST/GET /api/agents`, `GET/PUT/PATCH/DELETE /api/agents/{id}`, `GET /api/agents/{id}/credentials` — Owner manages their own Agents. Agent removal is blocked (409) while they still have open leads.

### Client + Member CRM
- `POST/GET /api/clients`, `GET/PUT /api/clients/{id}`, `PATCH /api/clients/{id}/status`, `GET /api/clients/lookup` — creating a client also creates its primary member in the same call, because a client with nobody to contact has no source for the `client_name` snapshot every downstream table carries. The lookup is the duplicate check that powers the Add Lead wizard; it searches the whole agency rather than the caller's own clients, so a walk-in already known to a colleague is found instead of created twice.
- `GET/POST /api/clients/{id}/members`, `PUT/PATCH /api/clients/{id}/members/{memberId}`, `POST/DELETE .../members/{memberId}/documents` — the roster and its identity documents. Only a name is required to add a member; passport and other fields fill in as the enquiry firms up. Clients and members are deactivated, never deleted, so past leads keep resolving.

### Lead + Proposal + Public link (the most complex slice)
- `POST/GET /api/leads`, `GET /api/leads/{id}`, `PATCH .../status`, `.../follow-up`, `.../assign` (Owner-only), `POST .../notes`, `POST/DELETE .../proposal-items`, `POST .../proposal-link`.
- `GET/POST /api/leads/{id}/members`, `PATCH /api/leads/{id}/members/{memberId}` — the traveller manifest. Travellers are either picked from the client's roster or created ad hoc and attached in one call; ad-hoc travellers join the client's roster so they are reusable next time. Removing a traveller means setting `DROPPED` with a reason, never a delete: by the time someone pulls out, their passport has usually been collected and their visa may already be filed.
- `GET /api/leads/{id}/timeline` — server-written activity stream (status changes, assignments, manifest edits). Read-only by design; agent-authored prose belongs in notes.
- The per-lead visa checklist moved from five booleans on `lead` to one set per traveller on `lead_members` — a single checklist shared by a fourteen-person group told an agent nothing about who was still missing what. `LeadDetailResponse.visaTracker` survives as a read-only roll-up across CONFIRMED travellers, true only when true for every one of them.
- `GET /api/public/proposals/{token}`, `POST /api/public/proposals/{token}/approve` — unauthenticated, pricing-safe by construction. The traveller manifest never crosses this boundary: it carries passport numbers and dates of birth behind a link that needs no login, so only the aggregate `guestCount` is exposed.
- Enforces two business rules from the BRD: a lost lead requires a reason, and only the Owner can reassign a lead to a different agent.

### Booking
- `POST/GET /api/bookings`, `GET/PUT /api/bookings/{id}`, `PATCH .../status`, `.../payment-status`. Profit is always recomputed server-side; cancelling requires a reason.

### Invoices
- `POST/GET /api/invoices/client`, `PATCH .../payment`, `POST/GET /api/invoices/supplier`, `PATCH .../status`, `GET /api/invoices/summary`. GST and payment status (Paid/Partial/Pending) are always server-derived.

### Visa (standalone module, distinct from the lightweight tracker embedded on a Lead)
- `POST/GET /api/visas`, `GET /api/visas/{id}`, `PATCH .../checklist`, `GET /api/visas/dashboard-summary`. Status is a formalized priority ladder (`REJECTED > APPROVED > SUBMITTED > APPOINTMENT_SCHEDULED > DOCUMENTS_PENDING`) computed from the checklist booleans — cleaner than the original mock UI's overlapping ad hoc logic.

### Dashboards + Reports
- `GET /api/dashboard/owner/summary`, `GET /api/dashboard/agent/summary` — live-computed KPIs, not client-side math.
- `GET /api/reports/revenue-trend`, `.../agent-leaderboard`, `.../lead-pipeline`, `.../lead-source-distribution`, `.../booking-type-distribution`, `.../conversion-funnel`, plus CSV export for leads/bookings/revenue/agents.

### Supplier search (Tripjack integration, search-only)
- `POST /api/flights/search`, `POST /api/hotels/search` — stateless supplier search feeding the proposal builder via the existing `POST /api/leads/{id}/proposal-items/batch`. Vendor is a `FlightSearchProvider`/`HotelSearchProvider` strategy, `mock` by default (`FLIGHT_SUPPLIER`/`HOTEL_SUPPLIER=mock`); a real vendor is a `@ConditionalOnProperty` implementation with no caller changes.
- `PUT/GET /api/supplier-credentials`, `GET .../{provider}/reveal` — owner-only, per-agency credentials in the tenant schema (`supplier_credential`), AES-256-GCM encrypted with the same key as agent password retrieval. Every reveal is logged, never cached.
- No booking/ticketing call exists yet — see "Explicitly out of scope" below.

## Tenant schema rebuild (Client / Member party model)

The tenant schema was rebuilt from scratch to replace `customer`/`family_member` with the
`client` → `member` → `lead_members` spine. What went and what arrived:

**Dropped**: `customer`, `family_member`, `customer_document`, `family_member_document`,
`customer_interaction`, `proposal_item`, `lead_note`.
**Added**: `client`, `member`, `member_documents`, `lead_members`, `lead_proposal`,
`lead_notes`, `lead_timeline`. `lead` was rewritten; `booking`, `visa` and `client_invoice`
had `customer_id`/`customer_name` renamed to `client_id`/`client_name`.

`V1__init_tenant_schema.sql` was **rewritten in place** and the old tenant `V2` deleted, rather
than adding a `V3`. That is normally forbidden by blueprint §2.5 ("never edit an applied
migration") and was allowed exactly once, because nothing had been deployed anywhere and the
only existing schemas were throwaway local ones. The rule holds again from here: add `V2`,
`V3`, … and never touch `V1`.

Three deliberate departures from the whiteboard design, each because the drawn version was
unsound rather than merely incomplete:

1. **No `age` column on `member`.** Pax type depends on age at the *travel* date. A stored age
   silently mis-fares a child who turns 12 between enquiry and departure. Derived by
   `util/PaxTypeCalculator` instead; boundary cases are covered by tests.
2. **`lead_members` is a real entity, not a bare link table.** It carries its own id, an
   inclusion status (`TENTATIVE`/`CONFIRMED`/`DROPPED`), a drop reason, and a per-traveller
   document checklist. A pure link can only say in or out, so removing a traveller would erase
   the fact that their passport was collected and their visa filed before they pulled out.
3. **`member` carries passport number, expiry and nationality as data.** `member_documents`
   stores the scan; ticketing and visa filing need the values, and a scan is not queryable.

Two additions the whiteboard omitted but the existing code needs: `client.agent_id`/`agent_name`
(agent-scoped visibility across every module filters on it) and `client.name` (the source of the
`client_name` snapshot on leads, bookings, invoices and visas).

Four invariants are enforced by partial unique indexes rather than by convention: one active
client per identifier, one primary member per client, one manifest row per (lead, member), and
one lead per public proposal token. All four were exercised against a real Postgres instance.

**Known limitations, accepted and out of scope for this change**: `booking` is still
single-traveller and has no `lead_id`, and `visa` is still one case per client. A 14-pax group
lead therefore cannot yet convert to one multi-traveller booking, and a family visa application
is not expressible as a single case.

**Frontend impact**: `travel-crm-main/lib/api/` is broken by this change. The customer endpoints
are gone and the lead, booking, invoice and visa payloads changed shape. Rewiring it is a
separate task and has not been started.

## How this was verified

Originally (v1 build), every module was tested with real `curl` requests against the running
server and a real Postgres database — not just "it compiles." That manual process caught and
fixed two genuine bugs (the Hibernate enum-array mapping bug and a timestamp-before-flush bug),
but manual curl runs can't be re-run, so nothing guaranteed the claims stayed true as the code
changed.

The remediation pass replaced that with an automated suite (now **99 test methods**) (`mvn verify` runs all of
it, plus the blueprint compliance audit) that converts every one of those manually-verified
claims into an executable assertion:

- **Unit tests** (26) — `MarginCalculator`, `VisaStatusCalculator`'s priority-ladder overlap
  resolution, `IdGenerator`, `UniqueIdResolver`'s retry/exhaustion behavior, `CsvWriter`, and
  `TenantSearchPathUtil` — the last of these is the SQL-injection allowlist guard from blueprint
  §3.3, tested directly against injection payloads (`abc; DROP TABLE tenant`, `abc'--`).
- **Service tests** (18, Mockito) — GST is always computed from the server-side rate regardless
  of client input; booking profit is always recomputed from current cost fields, never trusted
  from a stale stored value; the mandatory-reason rule for a Lost lead; the agent-scoping fix
  (an Agent can never touch a lead assigned to someone else, an Owner can touch any); agent
  removal is blocked while non-terminal leads remain assigned; and an unknown vs. an expired
  proposal token produce the byte-identical error message.
- **Controller slice tests** (14, `@WebMvcTest` + real Spring Security) — the public proposal
  endpoint's raw JSON body is asserted to never contain `netCost`, `margin`, `marginPercent`,
  `status`, `priority`, `source`, `assignedTo`, `notes`, `visaTracker`, `phone`, `email`,
  `budget`, `lostReason`, or `customerId` (this is the strongest form of this check — it fails
  the build if any of those fields is ever added to the response, not just at the DTO-type
  level); supplier invoices return 403 for an Agent and 200 for an Owner; only an Owner can
  reassign a lead; and the full blueprint §6.1 exception→status contract is exercised directly.
- **Integration tests** (5, Testcontainers Postgres) — **written but not yet run**: Docker isn't
  installed on this machine. `TenantIsolationIT` and `ProposalLinkResolutionIT` test-compile
  cleanly against the real service signatures and are correctly excluded from the default
  Surefire run (Maven's default pattern only matches `*Test.java`, not `*IT.java`), so `mvn
  verify` neither runs them nor depends on Docker. Run them once with Docker available before
  trusting the result — see `docs/REMEDIATION_PLAN.md` §T5.5.

Cross-tenant isolation and the agent-scoping fix were also independently confirmed manually
(two real agencies, real HTTP requests) during both the original build and the remediation pass.

## Blueprint compliance

`scripts/blueprint-audit.sh` mechanically checks the source against 14 rules from
`BACKEND_BLUEPRINT.md` (tenant isolation, no entities leaking out of controllers, `@PreAuthorize`
coverage, money as `BigDecimal`, collision-checked IDs, `@Schema` on every DTO field, test
coverage, and more). It runs automatically in `mvn verify` and currently reports:

```
$ ./scripts/blueprint-audit.sh
...
RESULT: COMPLIANT
```

Three deliberate, documented deviations from the blueprint remain (class-level `@PreAuthorize`
instead of per-method, REST-style URLs instead of the blueprint's `/save`/`/list` convention,
and `/api/public/**` as a second `permitAll` surface) — see `docs/REMEDIATION_PLAN.md` Appendix B
for why each one stands.

## Running it locally

```bash
cd "/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
set -a; source .env; set +a
./mvnw spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

Seeded demo accounts (all password `Passw0rd!`) — only created when `.env` has
`SEED_DEMO_DATA=true` (the local `.env.example` sets this; it must never be `true` in a deployed
environment, see `docs/DEPLOYMENT.md`):
- Platform Admin: `admin@travelos.com`
- Agency Owner: `owner@globalexplorer.com` (Global Explorer Travels)
- Agent: `liam@globalexplorer.com`

## B2B platform slice 2: wallet (feat/b2b-platform)

Public migration V18 and the wallet module. Agencies hold a prepaid INR wallet: `WalletService` posts append-only `wallet_entry` rows under a row lock (idempotency key per posting; holds move `held` only; never negative; a shortfall is a DUE `platform_receivable` settled by the next credit). Top-ups come by bank transfer (proof upload, unique UTR, admin approves a possibly different amount, 409 on a second approval) or by Razorpay (signed idempotent webhook, orphan table for events that arrive before the order, reconcile job for stuck PENDING rows, expiry). `PAYMENT_PROVIDER` defaults to `stub`, whose Simulate payment endpoint works only on a wallet flagged test. Platform admins adjust, close (refused with an open hold, DUE receivable or PENDING top-up) and flag test wallets; every action is audited. Env: `PAYMENT_PROVIDER`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`. Verification: `./mvnw verify`, `e2e/wallet.spec.ts`, and wallet rules in the full-regression `invariants.py`. Booking debits and holds arrive in slice 3.

## B2B platform slice 3: flight booking (feat/b2b-platform)

Public migrations V19-V22 (`platform_booking`, `platform_booking_event`, confirm hash, `resolved_failed_at`, 120-character supplier ids) and tenant migration V78 (Voyra supplier vendor and the "Voyra Wallet (advance to supplier)" account). `PlatformBookingService` is the state machine and the only booking caller of `WalletService`; `FlightBookingProvider` has a mock default and a best-effort Tripjack implementation (shapes from the spec, to be corrected by the slice 0 spike). Agency endpoints `/api/platform-bookings` (review, confirm, abort, cancel-quote, cancel, list, get), platform `/api/platform/bookings` (list, get, resolve, resolve-cancel). `book` goes out once per review id and a timeout moves the row to BOOKING for `PlatformBookingPollJob`. Pricing is stamped at review; agencies see the published fare struck through; never Voyra's earnings, net, base or supplier ids. Cancellation refunds less `voyra_cancel_fee` (platform setting, default 0) and recalls the commission net of TDS. The CRM mirror is written by a separate tenant bean after the debit commits and retried by `PlatformBookingMirrorRepairJob`. Deploy notes: any database that ran tenant V78 from commit `02d186d` needs `flyway repair` on each tenant schema (V78's checksum changed in `b9542da`); while the booking provider is mock, create FLIGHT rates only for agencies with test wallets (a mock booking can only use a test wallet); changing the platform supplier credential is refused while bookings are in flight (HELD, BOOKING, UNKNOWN, CANCELLING). Verified: `./mvnw verify` 1,318 tests, blueprint audit COMPLIANT; Playwright 155 passed against a fresh production build (new `platform-flight-booking.spec.ts`); invariants include BOOKING_RULES (hold closed, one debit, commission once, recall net of TDS, refund within the debit, net formula).

## B2B platform slice 4: hotel booking (feat/b2b-platform)

No new migrations. The slice 3 engine became product-aware: `BookingProviderRegistry` returns the `FlightBookingProvider` or the `HotelBookingProvider` for a row's product, and `PlatformBookingService`, the poll job, cancellation and the CRM mirror dispatch through it, so there is one state machine, one set of wallet keys and one set of invariants. `HotelBookingProvider` has `MockHotelBookingProvider` (default; `app.hotel-booking.provider` / `HOTEL_BOOKING_PROVIDER`, switches under `app.hotel-booking.mock.*`; refund is the whole total before check-in minus 2 days, else 0) and a best-effort `TripjackHotelBookingProvider` (review calls `/hms/v3/hotel/review` only, then `/oms/v3/hotel/book`, `booking-details`, `cancel-booking`, all unverified until slice 0). Hotel search and detail carry the agency-priced fields per room option (`publishedFare`, `displayedFare`, `agencyEarns`, `bookable`); the base is the room rate excluding taxes, else `BASE_FARE_UNAVAILABLE`. `POST /api/platform-bookings/hotels/review` reprices an option for given dates and rooms; confirm needs a lead guest per room and the lead guest's PAN is optional (captured; no supplier "PAN mandatory" flag is read yet, on the Tripjack checklist). The cancel quote adds `cancellationPolicy` and `freeCancellationUntil`. A hotel mirror is a HOTEL CRM booking with a `HOTEL_VOUCHER`. Who may book: owner, or an agent with HOTEL. Verified: `./mvnw verify` 1,377 tests, blueprint audit COMPLIANT; Playwright 166 passed on a fresh production build (158 plus the 8 tests of `platform-hotel-booking.spec.ts`); invariants hold, with a new hotel mirror and voucher rule; matrix rows PH1-PH12.

## B2B platform slice 5: CRM SKUs, subscriptions and entitlements (feat/b2b-platform)

Public migrations V23-V25. `subscription` and `subscription_invoice` plus `EntitlementService` (skus, seats, readOnly, cached in `TenantCache` beside the tenant status) and one `EntitlementInterceptor` driven by the explicit `ControllerSkuTable` (controller to SKU or NEVER_GATED); a contract test and `blueprint-audit.sh` fail the build on an unclassified controller. 402 `SKU_REQUIRED` / `READ_ONLY`; SUSPENDED keeps its 423; READ_ONLY never logs anyone out or deletes anything, and the free portal (wallet, platform bookings, search, profile, subscription) stays writable. Seats are counted from active agent rows and checked on create and reactivate under a lock on the subscription row; the owner may always cut off a staff login. Billing: GST tax invoices (CGST+SGST same state, else IGST, SAC 998314) numbered `VOY-SUB-<yyyy>-<seq>`, raised by the daily lifecycle job or by "raise now", paid through the gateway (webhook plus reconciliation, once per payment id) or marked paid by bank; void for unpaid ones; PDFs re-rendered on settle or void. Platform and agency APIs and `GET /api/auth/entitlements` as in CLAUDE.md. Backfill: every live agency gets a 90-day complimentary Suite, the agency named in `founding_agencies` a permanent waiver. Verified: `./mvnw verify` 1,519 tests, blueprint audit COMPLIANT; Playwright 178 passed on a fresh production build (166 plus the 12 tests of `subscriptions.spec.ts`); invariants hold with three new subscription rules; matrix rows SK1-SK16.

## B2B platform slice 6: launch hardening (feat/b2b-platform)

Public migrations V26-V30; no new money paths. **Tables:** `notification_outbox` (V26); `platform_statement_import` and `platform_statement_line` (V27; V30 drops the unique file hash so a file can be imported again); `platform_setting_request` plus settings `tripjack_live_ready` and `reference_fare_basket_hotel` (V28); `platform_booking.pax_purged_at` plus setting `pax_retention_days` (V29). **Endpoints:** `POST /api/platform/statements`, `GET /api/platform/pnl`, `GET /api/platform/dashboard`, `/api/platform/admins` (list, add, deactivate, reactivate), `POST /api/platform/settings/{key}/requests`, `GET /api/platform/settings/requests`, `POST .../requests/{id}/approve` and withdraw; `agencyStatus` on the login response and `GET /api/auth/entitlements`; `captchaToken` on `POST /api/public/agency-applications`. **Jobs:** `NotificationOutboxJob` (every minute), `PassengerDataPurgeJob` (03:45 daily), and the booking poll now also walks Tripjack CANCELLING rows (every 10 minutes) and skips rows the configured provider does not serve. **What it does:** after-commit email outbox with SMTP; platform P&L with Tripjack statement import (estimate kept, statement figure beside it, per-line idempotency); supplier readiness (agency-safe messages, first and last names, reviewed pax mix and pax type checks, hotel base-rate allow-list, async Tripjack cancel with a 24 hour overdue alert); the `tripjack_live_ready` second-approver switch with a startup guard, a PROD credential guard and a kill switch at review and confirm; supplier routing by the row's environment (`SUPPLIER_MISMATCH`, `BOOKING_UNAVAILABLE`); the second-approver independence rule over the admin creation chain for the go-live switch (agency pricing approval stays approver ≠ requester); DPDP purge of passenger data, expired outbox bodies and the mirror-pending skip; Turnstile on `/apply` create (fail closed); platform admins screen and dashboard tiles; suspended-agency banner; `DB_POOL_SIZE`. Operator steps and env vars are in the Deploy runbook of `pr-descriptions/b2b-01-foundation-backend-PR.md`. **Verified:** `./mvnw verify` 1,735 tests, blueprint audit COMPLIANT, plus the four Testcontainers ITs green (`NotificationOutboxRepositoryIT` 1, `DocumentNumberServiceIT` 1, `TenantIsolationIT` 5, `ProposalLinkResolutionIT` 2; they run outside `verify`, under colima with `DOCKER_API_VERSION=1.41`, `TESTCONTAINERS_RYUK_DISABLED=true` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`); frontend `tsc` clean, lint at the 22-error baseline, Playwright 178 passed; invariants hold; full Chrome regression of slices 1-6 CLEAN (`qa-reports/2026-10-26-feat-b2b-platform-full.md`, matrix rows incl. LH1-LH15). Mock switches `cancel-timeout` / `cancel-fail` (flight and hotel, off by default) drive an unanswered or refused cancel.

## Code review of slices 1-6 (feat/b2b-platform, 2026-10-26)

A whole-branch bug review plus a spec audit against `B2B_PLATFORM_DESIGN.md` (356 requirements) found 1 critical and 15 major issues. All are fixed with tests except one ruled no-code, and each fix was re-reviewed. Tenant status now reloads on every instance every `APP_TENANT_CACHE_REFRESH_MS` (30 s). Wallet adjustment keys are scoped per agency, and replaying a key on another wallet is refused. Card disputes now alert, and a won dispute is credited back. The subscription fixes cover void and re-raise, plan changes while an invoice is open, waivers or trials cutting a paid period short, and lifting READ_ONLY. Other fixes: mirror-cancel rounding for non-INR agencies, purging passenger data once a mirror is abandoned, the voucher price check against the invoice, the application row lock and the optional `APP_SECURITY_TRUST_CF_CONNECTING_IP` rate-limit key. Owner ruling: agency pricing rates need no second approver; the audit log records who set them. The accounts Billing page's unpaged booking load is open. Gates: verify 1,765 tests, ITs 9/9, Playwright 177/178 (one timing flake that passes on re-run), invariants hold. Report: `qa-reports/2026-10-26-feat-b2b-platform-code-review.md`.

## Agency plans: Basic, CRM Lite, Premium (2026-10-27)

Design: `docs/superpowers/specs/2026-10-27-agency-home-and-plans-design.md`. Agencies see three plans: **Voyra Basic** (`STARTER`, free), **Voyra CRM Lite** (new `LITE`: `CRM_CORE`, `STAFF_SEATS`, `REPORTS`, `VISA`; `LITE_INCLUDED_SEATS` 3 plus extra seats; one invoice line "LITE" at `sku_prices.LITE`) and **Voyra Premium** (`SUITE`, unchanged); `CUSTOM` stays admin-only. Public migration **V31** (`V31__agency_plans_lite.sql`) widens `subscription_plan_check` to allow `LITE` and adds `"LITE":2999` to `sku_prices` when absent (the setting now needs that key). Rulings: `ClientController` and `ClientSubResourceController` are `NEVER_GATED` (client management is Basic; READ_ONLY still refuses client writes); creating or reactivating an agent login needs `STAFF_SEATS` and an accountant login `ACCOUNTS_GST`, refused with 402 `{code: SKU_REQUIRED, sku}` (the interceptor's body, via `TenantWriteRefusedException.skuRequired`), before the seat count; existing logins are never cut off and deactivate/delete stay free. Endpoints: `GET /api/platform-bookings/summary?from&to` (owner, agents for their own bookings, accountants; IST days, default this month to today, at most 366 days; bookings created in range excluding REVIEWING/ABORTED, ticketed, cancelled, ticketed sales and agency earnings after TDS, per product, plus up to 5 upcoming ticketed trips by the travel date stamped at review and 5 BOOKING/UNKNOWN/CANCELLING rows; same hand-built mapper and leak test as the list); `PUT`/`DELETE /api/agency/profile/logo` (owner; PNG, JPEG or WebP by first bytes, at most 1 MB, stored under the tenant's `logo` category, the previous logo file deleted unless it is an onboarding KYC file) and `GET /api/agency/profile/logo` (every agency role, `Cache-Control: private, max-age=300`, 404 when none); `AgencyProfileResponse.hasLogo`, and the plain profile `PUT` no longer sets `logoKey`; `POST /api/agency/subscription/upgrade` (owner ruling 2026-10-27, replaced the first cut's upgrade-request alert: `LITE` or `SUITE` above the current plan, STARTER < LITE = CUSTOM < SUITE, else 400; returns an ISSUED invoice carrying `target_plan` (public **V32**), priced as the new plan plus any extra seats held, period from now, due in 7 days; the owner pays it through the usual invoice pay or simulate routes and `settle` switches plan, SKUs, period, ends any trial or waiver; the same plan again returns the open upgrade invoice, another plan voids it, an unpaid renewal invoice must be paid first (409); `SubscriptionLifecycleService` first voids upgrade invoices unpaid past due (`lapseUpgrades`) so an abandoned checkout never makes the agency PAST_DUE). `GET /api/agency/subscription` adds `planPrices` (LITE and SUITE monthly before GST). The Suite invoice line now reads "Voyra Premium (5 staff seats included)". Onboarding: KYC type `AGENCY_LOGO` (logo rules, optional, never counts toward PAN); approval sets the new tenant's `logo_key` to that document's file key, no copy. QA: `.claude/run/seed-plan-agencies.py` at the repo root creates QA Basic / Lite / Premium agencies through the API.

## Explicitly out of scope (per earlier decisions)

- Audit log and notification system.
- Supplier booking/ticketing (search only — see below; no PNR, no wallet, no ticketing call).
- GCS file storage (local disk only; the storage layer is already abstracted so this is a config flip + one new class later).
- Department-specific portals, monthly targets, and other full-BRD features beyond what the current UI needs.

## Next steps

1. **You**: send the GitHub username and SSH access when ready — the repo is `git init`'d locally with nothing pushed yet.
2. **Reset the local database and boot once.** The rewritten `V1` has been applied to a scratch
   Postgres database and its constraints exercised, but the app itself has not been started
   against it. Stop the running dev server, then drop and recreate `voyra_crm_dev` (all its
   content is regenerated by the demo seed on boot) and start the app — Flyway rebuilds the
   public schema and `TenantMigrationStartupRunner` rebuilds each tenant schema.
3. **Run the two integration tests**: install Docker, then run
   `./mvnw test -Dtest=TenantIsolationIT,ProposalLinkResolutionIT`. They're excluded from
   `mvn verify` and have still never been executed. `TenantIsolationIT` now also covers the
   `client`, `member` and `lead_members` tables.
4. **Frontend integration**: rewire `travel-crm-main/lib/api/` to the new client/member/manifest
   contract. This is now a fix, not just an enhancement — the frontend is broken against this
   backend until it happens.
5. **GCS migration**: when you're ready to move off local disk, add a `GcsFileStorageService` implementing the existing `FileStorageService` interface and flip `STORAGE_PROVIDER=gcs` — no other code changes needed.
6. **Deployment**: `docs/DEPLOYMENT.md` has the full Cloud Run runbook (Cloud SQL socket factory, secrets, the `cloudrun` Spring profile, deploy command, pre-deploy checklist) — the Dockerfile builds a non-root image. Nothing has actually been deployed yet; this is ready to execute once you have the GCP project set up.
7. **Multi-traveller bookings and per-member visas**: add `booking.lead_id` and a
   `booking_traveller` table, and move `visa` to `member_id`. Deliberately deferred; see the
   known limitations above.
8. Anything from the "explicitly out of scope" list above, if priorities change.

## Pricing rules (2026-10-27)

Owner ruling: shared, named discount and commission rules per product replace per-agency rates (design `docs/superpowers/specs/2026-10-27-pricing-rules-design.md`). Public migration **V33** adds `pricing_rule` and `agency_pricing_assignment`, seeds a Default 0 % discount and commission rule per product, and converts each agency's rate in force into named rules plus an assignment (no price change; scheduled or pending rows are not carried over; `agency_pricing` stays as history). Every agency without an assignment is on the Default rules, so an agency can always book (PRICING_NOT_CONFIGURED only if a Default rule were missing). Edits apply at once to all agencies on a rule; pct or allow-above changes bump `version`, which is part of the stamped `pricingId`, so a booking reviewed before an edit re-prices at confirm. A rule in use or a Default rule cannot be deleted (409 `RULE_IN_USE` / `DEFAULT_RULE`). Endpoints: `GET/POST /api/platform/pricing-rules`, `PUT/DELETE /api/platform/pricing-rules/{id}`, `GET /api/platform/pricing-rules/{id}/impact`, `GET /api/platform/agencies/{id}/pricing`, `PUT /api/platform/agencies/{id}/pricing/{product}`, `GET /api/platform/agencies/{id}/pricing/preview`. The old per-agency create / approve / withdraw / history routes are gone. Audit actions PRICING_RULE_CREATE, PRICING_RULE_UPDATE, PRICING_RULE_DELETE, PRICING_ASSIGN.


## One platform admin for everything (2026-10-27)

Owner ruling: nothing on the platform needs a second admin. The Tripjack live switch (`tripjack_live_ready`) is now turned on or off by the signed-in platform admin with the plain setting write `PUT /api/platform/settings/tripjack_live_ready` (audited as `SETTING_SET`), like every other setting. Removed: the request / approve / withdraw endpoints under `/api/platform/settings/.../requests`, `PlatformSettingRequestService`, `SecondApproverRule`, the `PlatformSettingRequest` entity and repository, and the withdraw-on-deactivate step in `PlatformAdminService`. The V28 `platform_setting_request` table and its rows stay as history; the `SETTING_CHANGE_*` audit actions stay so old audit rows still read. The startup guard (`TripjackLiveSwitchGuard`), the PROD-credential refusal and the `LIVE_BOOKINGS_PAUSED` kill switch are unchanged: they still read the flag.

## Agency vendors: no default seed, Voyra Platform locked (2026-10-30)

Owner ruling: agencies add, activate and deactivate their own vendors; no default vendors are seeded any more (`VendorDefaults` removed; `TenantDefaultsSeeder` seeds settings only). Existing tenants keep every vendor they have. Tenant migration **V79** adds `vendor.is_system`, inserts Voyra Platform where no row of that name exists, flags one (active first, oldest) as the system vendor and keeps it active, with a partial unique index so there is only ever one. The CRM mirror of a platform booking finds the vendor by `is_system`, not by name. `VendorService` refuses a change to the system vendor's name, service types or status with 409 `SYSTEM_VENDOR`; its tax, bank and contact fields stay editable so the mirror's supplier bills can carry Voyra's GSTIN and state. `GET /api/vendors` lists the system vendor first and `VendorResponse` carries `isSystem`.

## Platform suppliers (2026-10-30)

Owner feedback: suppliers are production only, there can be several, and each carries its own commission terms (design `docs/superpowers/specs/2026-10-30-platform-feedback-round-design.md` Phase C, as amended by "Changes after the adversarial review"). Public migration **V34** adds `platform_supplier_credential.name` (backfilled `Tripjack`), makes `provider` unique (one row per integration; the migration stops with an error rather than delete if a provider already has two rows) and adds `platform_supplier_product` (credential, product FLIGHT/HOTEL, `commission_estimate_pct` 0-100), backfilled for both products from the `commission_estimate_pct` setting (default 5). No setting row is deleted, so an old instance prices the same during a rolling deploy. `/api/platform/supplier-credentials` is now `GET` (list), `GET /{id}`, `POST` (create: name, provider, apiKey, isActive, products; always PROD on the integration's production host; 409 `SUPPLIER_EXISTS` when the integration has a row), `PUT /{id}` (name, apiKey (blank keeps it), isActive; the stored environment and base URL never change), `PUT /{id}/products` (terms only, never refused for bookings in flight, audited `SUPPLIER_TERMS_SET`) and `POST /{id}/test` (searches with that row's own key, audited with the credential id). The old `PUT` upsert, `GET /{provider}` and `POST /{provider}/test` are gone. Saving a key no longer needs `tripjack_live_ready` (the flag still gates bookings and startup); the in-flight refusal applies to create and to a key change or active toggle. Pricing (`AgencyPriceQuoter`, `AgencyPricingService` preview and impact) reads Voyra's estimated commission from `PlatformSupplierCredentialService.commissionEstimatePct(product)` (the active Tripjack row's terms, else 5); `discount_base` and `tds_rate` stay platform settings.
