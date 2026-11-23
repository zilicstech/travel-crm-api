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
- `POST/GET /api/platform/agencies`, `GET/PATCH /api/platform/agencies/{id}`, `GET /api/platform/agencies/{id}/credentials`, `PUT /api/platform/agencies/{id}/logo` — Platform Admin onboards an Agency; this *is* tenant provisioning (creates the schema, runs its migrations).
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

Design: `docs/superpowers/specs/2026-10-27-agency-home-and-plans-design.md`. Agencies see three plans: **Voyra Basic** (`STARTER`, free), **Voyra CRM Lite** (new `LITE`: `CRM_CORE`, `STAFF_SEATS`, `REPORTS`, `VISA`; `LITE_INCLUDED_SEATS` 3 plus extra seats; one invoice line "LITE" at `sku_prices.LITE`) and **Voyra Premium** (`SUITE`, unchanged); `CUSTOM` was replaced on 2026-11-01 (see Plan prices and settings below). Public migration **V31** (`V31__agency_plans_lite.sql`) widens `subscription_plan_check` to allow `LITE` and adds `"LITE":2999` to `sku_prices` when absent (the setting now needs that key). Rulings: `ClientController` and `ClientSubResourceController` are `NEVER_GATED` (client management is Basic; READ_ONLY still refuses client writes); creating or reactivating an agent login needs `STAFF_SEATS` and an accountant login `ACCOUNTS_GST`, refused with 402 `{code: SKU_REQUIRED, sku}` (the interceptor's body, via `TenantWriteRefusedException.skuRequired`), before the seat count; existing logins are never cut off and deactivate/delete stay free. Endpoints: `GET /api/platform-bookings/summary?from&to` (owner, agents for their own bookings, accountants; IST days, default this month to today, at most 366 days; bookings created in range excluding REVIEWING/ABORTED, ticketed, cancelled, ticketed sales and agency earnings after TDS, per product, plus up to 5 upcoming ticketed trips by the travel date stamped at review and 5 BOOKING/UNKNOWN/CANCELLING rows; same hand-built mapper and leak test as the list); `PUT`/`DELETE /api/agency/profile/logo` (owner; PNG, JPEG or WebP by first bytes, at most 1 MB, stored under the tenant's `logo` category, the previous logo file deleted unless it is an onboarding KYC file) and `GET /api/agency/profile/logo` (every agency role, `Cache-Control: private, max-age=300`, 404 when none); `PUT /api/platform/agencies/{id}/logo` (2026-11-01, SUPER_ADMIN, multipart `file`: `AgencyProfileService.uploadLogoForAgency` runs the owner's upload path for that tenant id, 404 for an unknown agency, `PlatformAuditAction.AGENCY_LOGO_SET` on entity `TENANT` with no file content, returns `AgencyProfileResponse`; the platform Add Agency form uploads an optional logo right after create); `AgencyProfileResponse.hasLogo`, and the plain profile `PUT` no longer sets `logoKey`; `POST /api/agency/subscription/upgrade` (owner ruling 2026-10-27, replaced the first cut's upgrade-request alert: `LITE` or `SUITE` above the current plan, STARTER < LITE < SUITE (CUSTOM existed then), else 400; returns an ISSUED invoice carrying `target_plan` (public **V32**), priced as the new plan plus any extra seats held, period from now, due in 7 days; the owner pays it through the usual invoice pay or simulate routes and `settle` switches plan, SKUs, period, ends any trial or waiver; the same plan again returns the open upgrade invoice, another plan voids it, an unpaid renewal invoice must be paid first (409); `SubscriptionLifecycleService` first voids upgrade invoices unpaid past due (`lapseUpgrades`) so an abandoned checkout never makes the agency PAST_DUE). `GET /api/agency/subscription` adds `planPrices` (LITE and SUITE monthly before GST). The Suite invoice line now reads "Voyra Premium (5 staff seats included)". Onboarding: KYC type `AGENCY_LOGO` (logo rules, optional, never counts toward PAN); approval sets the new tenant's `logo_key` to that document's file key, no copy. QA: `.claude/run/seed-plan-agencies.py` at the repo root creates QA Basic / Lite / Premium agencies through the API.

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

## Flight round trip and one Book with Voyra per card (2026-10-30)

Design `docs/superpowers/specs/2026-10-30-platform-feedback-round-design.md` Phases D and E (with the post-review changes 6, 7, 8 and 12). No migration. `FlightSearchQuery.returnDate` must be on or after `departDate` (400). Search offers and groups carry `direction` (ONWARD, RETURN, COMBO) and `legs` (`FlightLegResponse`; the top-level fields still describe the first leg), plus `specialReturnId` / `pairsWith` (Tripjack `sri` / `msri`) so the UI only pairs fares that can be booked together. `TripjackFlightSearchProvider` sends a second route for a round trip and reads `tripInfos.ONWARD`, `RETURN` and `COMBO` (the way back of a COMBO is the segments flagged `isRs`); the mock answers a round trip as ONWARD plus RETURN offers (ids ending `-R`, priced from the id like the rest), never COMBO. `POST /api/flights/search/paged` adds `tripType` (ONE_WAY, ROUND_TRIP_SPLIT, ROUND_TRIP_COMBO) and `returnFlights` (RETURN groups, same filters and sort, not paged); `flights` stays the paged ONWARD or COMBO list. A split round trip is ONE platform booking: `FlightReviewRequest.returnOfferId` (plus `return*` display hints the mock uses) makes `FlightBookingProvider.review(List<String> offerIds, ...)` review both price ids together (Tripjack `priceIds: [onward, return]`; the mock sums both fares, builds two `sI` segments and keeps its review id from the onward id). "Price changed" now ignores a difference of 0.02 or less, since the two legs are rounded separately. Tripjack booking details keep every distinct sector PNR, joined with " / " (fits `platform_booking.pnr` VARCHAR(40)); a refused pair gets its own fixed agency message. Book, poll, cancel, wallet keys and the mirror are unchanged (the mirror already reads out-and-back legs as a Round Trip). The live probe (`.claude/run/tripjack-probe.java`, local DB and key, free calls only) got HTTP 403 from this machine, so the round-trip fixtures (`src/test/resources/tripjack/flight-search-roundtrip-*.json`, `flight-review-roundtrip.json`) follow Tripjack's documented shape and are to be corrected by slice 0. Known limit: the statement import's PNR-only match will not match a booking whose PNR is two joined sector PNRs (the booking id match still works).


## New agencies start on Voyra Basic; demo checkout for upgrades (2026-10-30)

Owner ruling: no free Premium trial. `EntitlementService.newBasic` gives a new agency (and a tenant with no subscription row)
plan STARTER, status ACTIVE, no SKUs, no trial. With no payment gateway yet, an upgrade invoice (`target_plan` set) can be
paid through the stub gateway's simulated capture on any wallet and with simulation switched off
(`SubscriptionPaymentService.demoUpgrade`), so the upgrade can be shown to clients; renewals and top-ups keep the test-wallet
rule, and a real gateway (provider other than stub) ends the demo path. Frontend: `components/subscription/DemoCheckoutModal.tsx`.
Existing agencies are unchanged.

## Customer e-ticket and owner bookings (2026-10-31)

Design `docs/superpowers/specs/2026-10-31-customer-ticket-and-owner-booking-design.md`. **E-ticket**: public migration **V35** adds `platform_booking.ticket_details` (jsonb, one line per passenger and sector: `paxIndex` (0-based, in `pax_enc` order), sector, PNR, ticket number), written by `TripjackFlightBookingProvider` from each traveller's `pnrDetails` / `ticketNumberDetails` (the mock writes no lines and keeps one ticket number per booking, so a multi-passenger or round-trip mock ticket prints "-" for ticket numbers); `pnr` and `ticket_numbers` are unchanged for other readers. **V36** adds `platform_booking_ticket` (one row per download: `seq` assigned under the booking row lock, `markup`, the fare lines and total frozen at that moment, who and when; no PDF and no personal data). `PlatformBookingTicketService` serves `POST /api/platform-bookings/{id}/tickets` `{markup}` (owner, or an agent who may open the booking; FLIGHT and TICKETED only, else 409 `TICKET_NOT_AVAILABLE`; after the DPDP purge 409 `PASSENGERS_PURGED`; `TenantStatusInterceptor` lets a SUSPENDED agency make this one write), `GET .../tickets` (newest first, also accountants: `id, seq, createdAt, createdByName, markup, ticketPrice`) and `GET .../tickets/{ticketId}/pdf` (ticket looked up by ticket, booking and tenant after the visibility check, any mismatch 404; `Cache-Control: no-store`, `nosniff`, attachment `e-ticket-<PNR or booking id>-<seq>.pdf`). Markup: 0 to the published fare (`gross`), two decimals, messages "Markup cannot be negative", "Markup can have at most two decimals", "Markup can be at most the published fare". Price = `gross + markup`; `TicketFare` prints the base fare and, with a markup, one "Taxes, fees and charges" line (a named breakdown would leave a residual equal to the markup); with no markup it prints the supplier's named taxes and a non-zero "Other charges" residual, or one "Taxes, fees and charges" line when the breakdown is unreadable (the mock) or the residual would be negative. `TicketFacts` reads per-direction facts (segments, fare type, cabin, refundability, baggage) from the stored itinerary, splitting a COMBO on `isRs`; a row without `ticket_details` prints ticket numbers by position only for one direction with exactly one per passenger, and its PNR falls back to `platform_booking.pnr` on every direction. `ETicketPdfRenderer` (OpenPDF, A4) prints the agency header (logo via `AgencyLogoLoader`, now shared with the invoice PDFs), airline logos (`src/main/resources/airlines/`, a test checks every mock airline has one), flights, one passenger row per passenger per direction, fare details and fixed travel notes, with a "NOT VALID FOR TRAVEL" watermark when the booking is not TICKETED and "TEST BOOKING - NOT VALID FOR TRAVEL" when it is not PROD. The booking detail response adds `publishedFare`; the base fare appears only inside the PDF.

**Owner bookings** (owner ruling): the owner books in their own name. `issuedByAgentId` is removed from `FlightReviewRequest`, `HotelReviewRequest` and `PlatformBookingConfirmRequest` (an old client that sends it is ignored), `ISSUING_AGENT_REQUIRED` is gone, the `issued_by_agent_id` column stays as history, and the CRM mirror's agent is `bookedBy`, or the owner when that agent has since been deleted (so the mirror never fails for good). `BookingService.resolveOwningAgent` returns the owner (user id = tenant id, name `tenant.ownerName`) for an owner caller and for `SystemActor` with the tenant id, so an owner booking for a client in an agency with no agents mirrors DONE; `OwnerNameSyncService` re-syncs the owner's name on booking snapshots after a rename. An owner's Log Booking on an unclaimed service makes the owner its assignee (`assignedAgentId` = tenant id, status BOOKED, via `ServiceBookingStatusSync.onBookingLogged`), and a booking the owner logs on an agent's service is credited to the owner; agents who handle the type can still edit it (`ServiceInstanceService.assertEditAccess`) and the owner can reassign it to an agent. An owner platform booking inside an agent's lead is not in that agent's platform bookings (detail 404), but its CRM mirror stays readable to the service's assigned agent. The agent leaderboard and the agents export add an owner row. No migration resets old FAILED mirrors (live bookings are off; the repair job retries). Open supplier assumptions for the slice 0 spike: Tripjack returns `travellerInfos` in the order sent, and connecting-flight `pnrDetails` keys are per direction. e2e `travel-crm/e2e/platform-ticket.spec.ts`.

## Unified bookings (2026-10-31)

Design `docs/superpowers/specs/2026-10-31-unified-bookings-design.md`, plan `docs/superpowers/plans/2026-10-31-unified-bookings.md`. No migration. For an agency with CRM Core a Voyra booking is one row in the CRM Bookings list, vendor "Voyra Platform", from the moment the booking is sent to the supplier.

- **Client required**: `FlightReviewRequest.clientId` and `HotelReviewRequest.clientId` are `@NotBlank` (400 "Client is required"); `requireLinks` stays the second check.
- **Mirror reconciler**: `PlatformBookingService.queueMirror` marks `mirror_status = PENDING` and records `MIRROR_QUEUED` in the same transaction on every move to BOOKING, TICKETED, FAILED (supplier answer, poller, admin resolve) and CANCELLED (`cancelLocked`, `settleConfirmedCancel`, `resolveCancel`); UNKNOWN and CANCELLING mark nothing. `PlatformBookingMirrorService.sync` runs outside any transaction (in confirm right after the book call, before `awaitOutcome`) and calls `PlatformBookingMirrorTenantService` phases in order, each `REQUIRES_NEW` as the `SystemActor`: ensure (`BookingRepository.lockPlatformBooking`, a transaction-scoped `pg_advisory_xact_lock(hashtext(:id))`, then `BookingService.createVoyraPending`: PENDING, vendor Voyra Platform, net cost = displayed, selling price = published + current markup, voucher with purchase rate, no PNR, no bill, no lead-service change), ticket (`confirmVoyraTicket`: PNR, tickets, bill approved and paid from the Voyra wallet account, `onBookingLogged`, CONFIRMED; a fail-cancelled row is revived with "Voyra ticketed this booking after all"), fail (`failVoyraBooking`: CANCELLED, reason "Voyra could not complete this booking; nothing was charged", NOT_APPLICABLE, no money; only from PENDING) and cancel (unchanged CRM cancel). `PlatformBookingMirrorRepairJob` caps attempts by `MIRROR_FAILED` events since the newest `MIRROR_QUEUED`; the purge's abandoned-mirror check shares that count. A mirror error never blocks confirm (`scheduleMirror` logs and continues); `applySupplierResult` syncs only when the status moved under the lock.
- **Markup**: current markup = newer of the newest ticket download and the newest `MARKUP_SET` event (`PlatformBookingResponse.markup`, 0 when none). `PATCH /api/platform-bookings/{id}/markup {markup}` and a ticket download with a markup call `PlatformBookingMirrorTenantService.setMarkup` (markup-only reprice at the voucher's stored rate; 409 `PRICE_BELOW_INVOICED` below the invoiced total, 409 `MARKUP_NOT_APPLICABLE` unless the CRM row is CONFIRMED / COMPLETED). The PATCH also refuses a platform booking that is not TICKETED (409 `TICKET_NOT_AVAILABLE` "Markup can be set only on a ticketed booking"), returns 404 without a CRM booking or without CRM Core, 402 on a READ_ONLY plan and 423 while SUSPENDED. Only the download's CRM write is skipped without CRM Core or on READ_ONLY, and runs on SUSPENDED (the ticket download is the one write a suspended agency may make). A download never fails because the CRM row is not confirmed yet (it swallows `MARKUP_NOT_APPLICABLE`; the ticket phase reprices with the current markup). An empty id list skips the markup lookup. The below-invoiced guard counts issued invoices only (`InvoiceLineSource.invoiced` skips drafts). A `MARKUP_SET` event records `by` / `byName`, because the CRM reprice runs (and its audit entry reads) as the system.
- **Client money guard**: `BookingService.waitingForVoyra` refuses the lifecycle payment, a deposit receipt, a new invoice draft (single booking or from vouchers) and issuing an invoice that covers a Pending Voyra row with 409 `WAITING_FOR_VOYRA`; the Accounts "New invoice" picker leaves those bookings out; the lifecycle drops RECORD_PAYMENT and GENERATE_INVOICE for it; `BookingNextStep` says "Waiting for Voyra".
- **Verified on Postgres (2026-11-01)**: the advisory lock and the empty-list markup lookup ran in the real-backend e2e (every Voyra booking, the empty platform list of a Visa-only agent); no fix needed.
- **Basic, owner ruling 2026-11-01**: no CRM record on Basic (`PlatformBookingMirrorService.sync` defers to NONE without CRM Core and a CRM booking); `PlatformBookingMirrorRepairJob` backfills an agency's NONE rows with a client once it has CRM Core (`backfill`, `findTenantsWithUnmirroredBookings`). Same day: a fail-cancelled Voyra booking is off the refunds list and offers no CROSS_CHECK; a markup reprice audits as the user (`SystemActor.call(tenant, Author, work)`); message fixes for `MANAGED_FROM_PLATFORM`, the markup digits and Set markup on Basic. Then: backfill only rows with a `MIRROR_QUEUED` event; cross-checking a fail-cancelled booking is refused; the mirror dates every phase on the platform's own time (`FinancialYear.dateOf`, `SupplierInvoiceService.draftForVoucher(..., billDate)`, a `BookingCancellationService.cancel` overload taking the cancel time and supplier posting date), except the client's tax documents on a cancel, which stay today.
- **Tests**: backend `mvn verify` 3500 tests, 100 % coverage, audit COMPLIANT; Testcontainers ITs green; e2e `travel-crm/e2e/unified-bookings.spec.ts` (two journeys behind mock switches, matrix 9h); invariants and matrix rows UB1-UB11.

## Suppliers in the database and multi-supplier search (2026-11-01)

Design `docs/superpowers/specs/2026-11-01-suppliers-plans-and-multi-supplier-search-design.md` Parts 1 and 2 (Part 3, plan prices, is a separate stream and migration V38). This supersedes the supplier provider properties, the live switch, the P&L and the statement import described in the sections above.

- **Migration V37 (public, expand only).** `platform_supplier_credential` loses `UNIQUE(provider)` and the partial active index and gains `UNIQUE (lower(name))`, so a provider can have several rows. `platform_supplier_product` gains `search_enabled` and `booking_enabled`, every toggle off. V37 inserts a "Mock supplier" row (`provider MOCK`, `environment MOCK`, `mock://local`, both products, toggles off, empty stored key meaning no key) and stamps `credential_id` on every MOCK-environment booking that had none. It sets `commission_estimate_pct` DEFAULT 5 (an old instance still reads it) and raises an exception on a COMMISSION rule with `allow_above_earned = false` whose `pct` exceeds its product's estimate, or a `discount_base` other than GROSS. Nothing an old instance reads is dropped: `is_active`, `commission_estimate_pct`, `allow_above_earned` and the retired setting rows (`tripjack_live_ready`, `commission_estimate_pct`, `discount_base`, `reference_fare_basket`, `reference_fare_basket_hotel`) go in V39 in a later release.
- **Suppliers.** `/api/platform/supplier-credentials` (GET, GET /{id}, POST, PUT /{id}, POST /{id}/test; no delete). `name` unique ignoring case, at most 80 characters; `provider` TRIPJACK or MOCK, fixed at create; the base URL decides the environment (UAT `apitest.tripjack.com`, PROD `api.tripjack.com`, MOCK `mock://local`) and the environment never changes, so a production account is a new row. API keys are encrypted, write-only and masked to the last 4; a blank key on update keeps the stored one. A MOCK row cannot be created on the `cloudrun` profile (400 `PROVIDER_UNAVAILABLE`); existing mock rows are still served. Audit `CREDENTIAL_SET` lists changed field names only.
- **Wiring.** The properties `app.flight-supplier.provider`, `app.hotel-supplier.provider`, `app.flight-booking.provider`, `app.hotel-booking.provider` (env `FLIGHT_SUPPLIER`, `HOTEL_SUPPLIER`, `FLIGHT_BOOKING_PROVIDER`, `HOTEL_BOOKING_PROVIDER`), `SupplierStartupLogger`, `BookingProviderRegistry`, `TripjackLiveSwitchGuard`, `requireLiveAllowed` and 409 `LIVE_BOOKINGS_PAUSED` are gone. `SupplierRegistry` has `searchable(product)`, `bookable(supplierId, product)`, `supplierFor(booking)` and `serves(booking)` (the supplier row exists, its provider bean exists, its environment equals the booking's); `BookingSupplier.cancelIsAsync()` replaces the "environment is not MOCK" checks. Review stamps `credential_id` and `environment` from the chosen supplier; confirm, poll, cancel and admin resolve use `supplierFor(booking)`. A booking toggle off (409 `SUPPLIER_NOT_BOOKABLE` at review and confirm) never stops rows already sent. A name, base URL or key edit expires an open review at confirm; a toggle edit does not.
- **Search.** `SupplierFanOut` (8 threads, queue 64, overall deadline 40 s) asks every supplier with search on for flights and for hotels; failures are named in `suppliersFailed`; no supplier is 409 `NO_SUPPLIER`, all failed 503 `SUPPLIERS_UNAVAILABLE`. A flight page with failures is not cached; a partial hotel result is kept under its `searchId`. Offer ids are namespaced `<supplierId>:<id>` (400 `OFFER_UNAVAILABLE` without a known namespace); a round trip must be on one supplier and a hotel and room option too (400 `MIXED_SUPPLIERS`). `FlightResultPaging.groupKey` (airline code, flight number digits after the code, departure, arrival, per leg, plus direction) puts one flight from several suppliers in one row with `cheapestPrice`, `cheapestSupplierName` and `supplierCount`; hotels are never merged.
- **Commission and profit tracking removed.** `/api/platform/pnl`, `/api/platform/statements`, `PlatformPnlService`, the statement import, the pricing rule impact and agency pricing preview endpoints and the dashboard `voyraNet30d` are gone; a commission rule pays exactly its percentage of the base, `discount_base` is GROSS, `voyra_earned*` and `voyra_net` are no longer written (unmapped; they stay null), TDS on agency commission stays. The statement tables and `platform_setting_request` stay as history.
- **Rulings.** The mock row's key is blank; `commission_estimate_pct` DEFAULT 5; the group key strips the airline code before taking flight-number digits; the offer's `supplier` string is the supplier row's name; the e2e global setup switches every other supplier's search off and leaves it off.
- **Tests and tools.** Backend gate green at 100 % coverage; invariant "every platform booking past REVIEWING has a `credential_id` that exists" replaces the two `voyra_net` rules; matrix rows SU1-SU5 and MS1-MS6; e2e `platform-supplier.spec.ts` and `multi-supplier-search.spec.ts`; `.claude/run/seed-mock-supplier.py` switches the local Mock supplier on.

**Deploy notes for V37.**
1. Deploy the backend before the frontend (the new frontend reads `suppliers` and `suppliersFailed` from the search responses). Take a database backup first.
2. Every toggle is off after V37. Before switching traffic read each environment's `FLIGHT_SUPPLIER`, `HOTEL_SUPPLIER`, `FLIGHT_BOOKING_PROVIDER`, `HOTEL_BOOKING_PROVIDER` and `tripjack_live_ready`, set the Tripjack row's toggles to match in Platform > Settings > Suppliers (production today: Flight search on, Flight booking off until wanted, Hotel off), then remove the four variables from Cloud Run (they are ignored). Switch any booking toggle on only once no old revision is running (old pollers included): old code throws a NullPointerException on the null `voyra_*` columns after the wallet debit or refund commits, leaving rows BOOKING or CANCELLING, and books any TRIPJACK row with the `is_active` account's key.
3. Count PROD rows in HELD, BOOKING, UNKNOWN or CANCELLING before the deploy and confirm the Tripjack row is PROD; those rows keep settling through their supplier.
4. V37 refuses on a capped commission rule above its estimate or a `discount_base` other than GROSS: fix the data and rerun.
5. During the rolling deploy an old instance maps `SupplierProvider` without `MOCK`, so the admin Suppliers list can answer 500 on an old instance until it is replaced; and an old instance can create MOCK-environment platform bookings with `credential_id` NULL after V37, which the new code will not serve. After the deploy, check for such rows (`platform_booking` where `environment = 'MOCK'` and `credential_id` is null); V39 can repeat V37's backfill UPDATE.
6. The drops (`is_active`, `commission_estimate_pct`, `allow_above_earned`, the retired setting rows) wait for V39 in a later release; `PlatformSettingService.list()` still returns the retired rows until then.

## Plan prices and settings (2026-11-01)

Plans are STARTER / LITE / SUITE only; the CUSTOM plan is gone. Plan prices are edited on the platform Plans tab and frozen on each subscription, so a price change never moves an existing agency's bill. Design and plan: `docs/superpowers/plans/2026-11-01-settings-and-plan-prices.md`.

- **V38** (public): `subscription.plan_price_monthly` and `seat_price_monthly`, NUMERIC(12,2) NOT NULL DEFAULT 0, backfilled from `sku_prices`; every CUSTOM row becomes SUITE if it held ACCOUNTS_GST, else LITE, with seats = extra over the plan's included 3 (LITE) / 5 (SUITE); plan CHECK is now STARTER / LITE / SUITE.
- **Rulings**: (1) the new columns have `DEFAULT 0` because an old instance still creates Basic agencies without them during a rolling deploy (matches STARTER's 0). (2) A converted CUSTOM row keeps its exact bill: frozen plan price = its non-seat SKU prices + `LEAST(seats, included) x STAFF_SEATS` (when it held STAFF_SEATS), extra seats = `GREATEST(seats - included, 0)`, so plan + extra seats equals its old renewal to the paisa. (3) `raiseUpgrade` stamps `billingDetails.seatPriceMonthly` (today's STAFF_SEATS price) because an upgrade with no extra seats has no seat line; `settle` freezes it, and an upgrade invoice raised before this release (no stamp) takes its plan price from its plan line and leaves the seat price as it was. (4) New Basic rows freeze 0 and 0 (`EntitlementService.newBasic` is static, has no settings); STARTER prices are never billed and every move off STARTER sets both. (5) Prices are set only when the plan changes (`update`, and `extendTrial` when it changes the plan); a seats or cycle change keeps them; moving to Basic freezes 0 and today's seat price. (6) Prices are written by `PlatformPlanService`, not `PlatformSettingService.set`, so a change is audited once as `PLAN_PRICES_SET` (from/to) and the other `sku_prices` keys stay for old instances.
- **API**: `GET/PUT /api/platform/plans` (SUPER_ADMIN, `PlatformPlanController`, NEVER_GATED); `sku_prices` is read only for LITE, SUITE, STAFF_SEATS (each > 0, at most 1000000, at most 2 decimals; other keys ignored); `PlatformSettingService.planPrices()`; `SubscriptionUpdateRequest(plan, seats, billingCycle)`; `AgencySubscriptionResponse.currentPlanPriceMonthly`. Billing `lines(s)` bills the frozen plan price plus extra seats times the frozen seat price (annual = 10 months); a zero total raises no renewal; `raiseUpgrade` prices at today's Plans-tab prices.
- **Frontend**: Settings tabs are Suppliers, Pricing rules, Plans, Admins (General tab removed); Plan & Billing shows a "Price" row; the admin subscription card has no Custom option or SKU checkboxes. See `travel-crm/CLAUDE.md`.
- **Deploy notes**: take a backup before V38. V38 logs one NOTICE per converted Custom agency (old SKUs and seats, new plan, frozen price); read them in the deploy log. An open Custom renewal invoice keeps its lines and settles into the converted plan. V39 (next release) rewrites `sku_prices` to the three keys and may drop the column defaults. On a local database, V38 goes on only after V37. **Order**: this branch ships V38 without V37 and Flyway has no out-of-order, so merge and deploy it only together with, or after, the V37 (suppliers) stream; deploy the backend before the frontend (a new frontend on an old backend 404s the Plans tab and the Price row shows undefined). **Zero prices**: before deploy check `sku_prices` has LITE, SUITE and STAFF_SEATS each above 0; do not deploy across 00:15 (lifecycle job on an old instance bills a converted row at `sku_prices.LITE`). V38 logs a WARNING for a converted Custom row at price 0 and for any LITE/SUITE row left at 0; read them in the deploy log. After deploy run `SELECT tenant_id, plan, status, plan_price_monthly, seat_price_monthly FROM subscription WHERE plan IN ('LITE','SUITE') AND (plan_price_monthly = 0 OR seat_price_monthly = 0)` and remedy each hit (an admin moves the plan off and back, which freezes today's prices, or a per-row fix); an old instance changing a plan during the rolling deploy can leave STARTER's 0.
- **Tests**: matrix rows SK4 (Lite, 3 seats), SK17, SK18; e2e `e2e/platform-plans.spec.ts`, `e2e/subscriptions.spec.ts` (moved off Custom).

## Flyway consolidated into one baseline per location (2026-11-13)

For the fresh production start the owner asked for one migration instead of the chain. `db/migration/public/V1__baseline.sql`
replaces public V1-V38 and `db/migration/tenant/V1__baseline.sql` replaces tenant V1-V79. Both were generated by replaying the
old chain into an empty database with `psql`, dumping the final schema with `pg_dump --schema-only` (tenant qualifiers stripped
so `TenantFlywayMigrator` creates it in each `tenant_<id>`; `public.gin_trgm_ops` stays qualified, pg_trgm is put in public by
the migrator), and writing the seed rows as the old migrations' own expressions (new ids, `now()`, `CURRENT_DATE`) rather than
dumped literals. A database built from the two baselines was compared with one built by the old chain: the schema dumps differ
only in how Postgres prints `IN (...)` check lists after a round trip, and every seed row matches. The schema is kept exactly as
the chain left it: retired columns and settings (`is_active`, `commission_estimate_pct`, `allow_above_earned`, `voyra_earned*`,
`voyra_net`, the statement tables, `tripjack_live_ready`, the old `sku_prices` keys) are still there; dropping them is the next
public migration, `V3`. Public `V2__remove_seeded_mock_supplier.sql` deletes the baseline's "Mock supplier" row (and its two
product rows) while no platform booking names it, so production starts with no mock row; local QA creates one through the API
(`.claude/run/seed-mock-supplier.py`, the e2e fixtures' `ensureMockSupplier`). `PlatformSuppliersMigrationIT` (V36 to V37 on old data) was
deleted with the chain. Existing databases need a re-baseline, not a migrate: the local `voyra_crm_dev` keeps its old history as
`flyway_schema_history_pre_squash` in public and every tenant schema, with one `BASELINE` row at version 1 in a new history table.


## Client correction round (2026-11-17)

From the client's "VOYRA B2B CORRECTION" list. **Flight result filters**: `FlightSearchPageRequest` gains `minPrice`, `maxPrice`,
`departBuckets` / `arriveBuckets` (`TimeBucket` EARLY, MORNING, AFTERNOON, EVENING), `fareTypes`, `refundTypes`, `flightNumber`,
`origins`, `destinations`, `baggage` (`BaggageFilter` CHECK_IN / HAND_ONLY); `stops` is now 0, 1, 2 (exactly two) or 3 (three or
more); the airline filter matches name or code. The page response carries facets from the unfiltered result (`priceMin`,
`priceMax`, `fareTypes`, `origins`, `destinations`, `airlineCodes`). Offers carry `refundType` (`FlightRefundType`; Tripjack `rT`
0 / 1 / 2, anything else non-refundable), `cabinBaggage`, `seatsLeft`, `mealIncluded`. **Fare rules before booking**:
`POST /api/flights/fare-rules {offerId}` asks the offer's supplier (`FlightBookingProvider.searchFareRules`; Tripjack
`/fms/v2/farerule` with `flowType: SEARCH`, unverified live; a refusal returns null rules). **Hotel filters**: `HotelSearchQuery`
gains `name`, `stars`, `minPrice`, `maxPrice`, `refundableOnly`, `boardBasis`, `sort` (`HotelSortOrder` PRICE / STARS), applied to
the kept list on every page request; the response adds `priceMin`, `priceMax`, `boardBases`; a hotel with no star rating in the
listing is left out while a star filter is on. Agencies no longer see supplier names on screen (the API still sends them).
Waiting for the owner: optional client (FIT), airline-wise name format, calendar fare, multicity, hold, special fare types,
hotel amenity / category filters and hotel name in the search bar.

## Walk-in client and special fare types (2026-11-17, owner rulings)

**Walk-in client** replaces "client required". Tenant `V2__walk_in_client.sql` adds `client.is_system` (one per tenant,
`uq_client_system`) and seeds "Walk-in Client" (B2C, identifier `WALK-IN`, or `WALK-IN-VOYRA` when taken, no credit limit) with
its primary member. It cannot be edited or deactivated and its primary member cannot be changed (409 `SYSTEM_CLIENT`); lookup
and duplicate checks leave it out; the list returns it first; `isSystem` is on the list and detail responses. `clientId` is
optional on the flight and hotel review: a lead keeps its own client; without a lead, an agency with CRM Core gets the client
it sent, and a blank one or any agency without CRM Core gets Walk-in (`PlatformBookingService.effectiveClientId`). At confirm the
passengers of a Walk-in booking are saved as its travellers (`PlatformBookingMirrorTenantService`, own `REQUIRES_NEW`, as the
system, never failing the booking): matched by name and date of birth, else created with relation OTHER; no title and no
document ID is stored on the member. Consequence: a CRM agency's walk-in customers share one ledger and advance pool, and its
invoices carry no GSTIN. Tenant migrations are now V1 and V2; the next is V3.

**Fare categories.** `FareCategory` REGULAR / STUDENT / SENIOR_CITIZEN on `FlightSearchQuery` and `FlightReviewRequest`
(null = REGULAR); the search cache key includes it. Tripjack gets `searchModifiers.pfts: ["STUDENT"]` for a non-regular
search only; review reads `conditions.dc.idm` into `documentIdRequired`; both are stamped in `pricing_snapshot` and returned on
the review response; confirm then needs `PaxInput.documentId` on every passenger (400 `DOCUMENT_ID_REQUIRED`) and sends it as
`di`. These Tripjack shapes come from other integrators' code and are **unverified with Tripjack**: confirm them from the
production console before relying on them. Armed Forces is reported unsupported; NDC and corporate fares arrive in a normal
search; Series was not found. The ID is kept only in the encrypted passenger blob and is never logged.

## Tripjack hotel booking aligned with the v3 documentation (2026-11-17)

Tripjack's public API documentation (tripjack.com/page/api-doc, Hotel API v3) showed the hotel booking provider was
written to guessed shapes and would have failed every live booking. No live hotel booking had been made (the Hotel
booking switch stays off in production). Fixed to the documented contract; still unverified against a live answer, and
Tripjack requires its 14-case hotel certification in UAT before live access.

- **Review** (`/hms/v3/hotel/review`, search host) sends `correlationId`, `optionId`, `reviewHash`, `hid` only. The
  pricing call's `reviewHash` and the search's `correlationId` ride in the option id the screens carry
  (`<optionId>|<reviewHash>|<correlationId>`, `TripjackHotelSearchProvider.OptionRef`; `HotelReviewRequest.optionId`
  now allows 600 characters). Review reads `option.compliance` (`panRequired`, `passportRequired`, `gstType`; also
  `ipr` / `ipm`) into `FareReview.compliance` (`HotelCompliance`).
- **Book, booking-details, cancel** run on the separate booker host (`app.hotel-supplier.tripjack.uat-booker-url`,
  default `https://apitest-hotel-booker.tripjack.com`; `prod-booker-url`, default `https://hms-booker.tripjack.com`).
  Book sends `type: HOTEL`, every guest per room (titles mapped, Mstr becomes Master; pt ADULT / CHILD), the lead PAN
  (on every guest when PAN is required), each guest's passport number when given, `deliveryInfo.code` (the dial code,
  new optional `PlatformBookingConfirmRequest.contactDialCode`, default 91) with the national number, and Voyra's GSTIN
  and legal name (platform settings) as `gstInfo` for a RESELLER or PASSTHROUGH rate. Booking-details maps the
  documented statuses (SUCCESS, FAILED/ABORTED, CANCELLED; IN_PROGRESS, PAYMENT_*, PENDING, ON_HOLD and
  CANCELLATION_PENDING stay pending), `hotelConfirmationNumber` and `order.amount` (variance check). Cancel is
  `POST /oms/v3/hotel/cancel-booking/{bookingId}` with no body; the quote and the accepted cancel work the refund out
  of `order.amount` less the penalty slab (`cnp.pd`, India time) in force now (non-refundable `cnp.inra`: 0).
- **Pricing**: the hotel base is `basePrice` when given (the total also holds the management fee `mf` / `mft`).
- **Guests**: every reviewed guest is required at confirm (one entry per adult and child, each titled), no two rooms
  may share a lead name, a room reviewed as needing PAN or passports is refused without them (400 `PAN_REQUIRED`,
  `PASSPORT_REQUIRED`; review response `panRequired` / `passportRequired`, pricing-snapshot keys `panRequired`,
  `passportRequired`, `gstRequired`). Mock switches `app.hotel-booking.mock.pan-required` / `passport-required`.
- Not done: hotel hold (`onholdAllowed`, confirm-book), showing the management fee as its own line, the booking list API.

## Hotel property type and amenity filters (2026-11-17, client correction list)

Tripjack's hotel listing carries no stars, type or amenities, so the Stars filter hid every live Tripjack hotel. The
search now reads `fetch-hotel-content` for the listed hotels before the list is kept (20 ids per call, all at once,
cached a week, waiting at most 6 seconds; late batches still fill the cache and a hotel without content simply has
none): stars, address and photo fill what the listing lacks, and `HotelOfferResponse.propertyType` /
`amenities` come from `property_type.name` and `amenities[].name`. `HotelSearchQuery.propertyTypes` (any of) and
`amenities` (all of, ignoring case) filter the kept list; the page carries `propertyTypes` (sorted) and `amenities`
(most hotels first) for the filter. The mock supplier's hotels have types and amenities. Search Hotels shows Property
type and Amenities (the 12 most common, Show all) in the filter column, and each card shows its type and first four
amenities. Amenity names are Tripjack's own, unseen until it runs on production.

## Cancellation requests (2026-11-18, owner rulings)

Why the old cancel showed a 504: "Cancel with Voyra" held one request open while the backend called Tripjack's
`amendment-charges` and then `submit-amendment`, each with a 60 s read timeout, so the Next `/api` proxy (120 s) or
DigitalOcean's edge gave up first and the browser got a 504 the backend never sent, with the row left CANCELLING or
back at TICKETED. Agencies no longer cancel at the supplier at all.

**Flow.**
- The agency raises a **cancellation request**:
  - flights: passengers (positions in the stored passenger list) and sectors (journeys `d0` onward, `d1` return); hotels:
    the whole stay;
  - a reason from `CancelReason` (Tripjack's groups: Normal, Full refund, Void, No show, Other; Other needs remarks);
  - remarks and up to 5 proof files (pdf / jpg / png / webp, 10 MB in all, magic bytes checked).
- The platform admin picks it up (agency can no longer withdraw), cancels on Tripjack's portal by hand, then completes
  it in Voyra (may change the reason, the passengers and sectors, and the figures), or declines it with a note.
- Completing posts the refund (`supplier refund - Voyra fee`, fee prefilled from `voyra_cancel_fee` on every request) and
  takes back commission, keyed per request: `pb-refund:<booking>:<request>` / `pb-recall:<booking>:<request>`.
- When every passenger is cancelled on every sector the booking becomes CANCELLED with `cancel_refund` the sum of the
  requests' refunds; until then it stays TICKETED (no new booking status).

**Data.** Public `V4__cancel_requests.sql`: `platform_cancel_request` (status OPEN / IN_PROGRESS / COMPLETING / COMPLETED /
REJECTED / WITHDRAWN, reason and final reason, passengers and sectors jsonb, remarks, supplier refund, charges, Voyra fee,
refund, recall, exceeds-share and offline flags, supplier reference, admin note; a partial unique index allows one active
request per booking) and `platform_cancel_request_file`. `feat/hotel-name-search` adds public V3 (this one was renumbered V4 so local databases take both). Both are now on this branch.

**Locking and money.** Every transition locks the booking row, then the request. Complete stores the figures first
(COMPLETING), posts through `PlatformBookingService.postCancelMoney` (shared with the old CANCELLING path, which keeps its
per-booking keys), then marks COMPLETED; a retry with other figures is 409 `FIGURES_LOCKED`. Caps: refund at most
`displayed` less refunds already posted (400 `REFUND_TOO_HIGH`), above the chosen passengers' fare share only with
`exceedsShare` (409 `EXCEEDS_SHARE`), recall at most what is still unrecalled (400 `RECALL_TOO_HIGH`). A booking no longer
TICKETED when a request is completed (cancelled another way) declines it with nothing posted. A closed wallet completes
offline (alert, nothing posted, `refundOffline` shown to the agency).

**Agency sees** status, reason and Voyra's final reason, passengers and sectors, its files, refund, charges, commission
taken back and Voyra's note; never Voyra's fee, Tripjack's refund or reference (`CancelRequestResponse`). The booking detail
adds `sectors` and `cancelledParts` (`"<passenger>:<sector>"`, `*` every sector); the e-ticket leaves cancelled parts out;
the agency summary takes partial refunds and recalls off sales and earned (GMV, like whole cancels, is unchanged).

**Platform.** `/api/platform/cancel-requests` (list, detail with prefills and caps, start, reject, complete, files; audited
`CANCEL_REQUEST_START` / `_REJECT` / `_COMPLETE` / `_FILE_VIEW` with ids and figures only). Admins are emailed when a request
is raised; the booker when it is completed or declined. The dashboard adds `cancelRequests` and `pendingTasks` (cancel
requests soonest travel first and flagged urgent within 24 h, then CANCELLING, UNKNOWN, KYC and top-ups; at most 20) with
`pendingTaskTotal`. The poller alerts once per request when Tripjack already shows a booking cancelled whose request is
still open after 2 hours.

**Mirror.** A completed partial request is mirrored as a supplier credit note numbered `VCR-<request>` on the CRM bill,
received at once into the Voyra wallet account (`BookingCancellationService.recordVoyraPartialRefund`); the whole cancel
then receives only what is left (`voyraPartialRefunds`). An invoice already credited for the booking gets no second client
credit note (existing rule), so a client credited by hand after a partial is not refunded twice.

**DPDP.** A booking with an active request is not purged; remarks, Voyra's note and proof files of finished requests are
cleared `pax_retention_days` after they finish (keys nulled in the transaction, files deleted after).

**Removed:** `GET /api/platform-bookings/{id}/cancel-quote`, `POST /{id}/cancel`, the supplier `cancelQuote` / `cancel`
calls (Tripjack flight amendment, Tripjack hotel cancel-booking, mocks), `CancelPreview`, `CancelQuote`, `CancelResult` and
the mock `cancel-timeout` / `cancel-fail` switches. Rows already CANCELLING at deploy still settle through the poller and
`resolve-cancel`. Tests: `PlatformCancelRequestServiceTest`, `CancelPartsTest`, `PlatformCancelRequestControllerTest`,
mirror and cancellation service tests, `CancelRequestIT`.

## Hotel search by name (2026-11-18, client PDF #7, owner rulings)

Tripjack's hotel listing takes hotel ids only, so a hotel cannot be searched by its name there. Voyra now keeps each
hotel supplier's hotel list in public V3 `hotel_catalog` (supplier, hotel id, name, city, country, stars, property
type; GIN trigram index on name; V3 also creates pg_trgm in `public`, which until now only the tenant migrator did)
and `hotel_catalog_sync` (one row per supplier). Owner ruling: the list is loaded **only** when a platform admin
calls `POST /api/platform/hotel-catalog/{supplierId}/refresh`, never on a schedule or at startup; the same call creates
the catalogue or refreshes it. It answers 202 and runs on a single thread of its own (never Spring's scheduler
thread); `GET /api/platform/hotel-catalog` shows each supplier's status, hotels held, last load and last error.

- **Load** (`HotelSearchProvider.catalog`, batches handed over on the calling thread). Tripjack: for each country in
  `app.hotel-catalog.countries` (default `INDIA`), `fetch-hotel-mapping {countryName, page, size 2000}` paged by its
  `totalPages` (capped at 1000), then `fetch-hotel-content` for those ids (`content-batch` 50, `parallel-calls` 2,
  `max-calls-per-second` 2) on a pool of its own and outside the search caches. A failed call is retried once after
  30 s. A content call that still fails is counted and skipped. A lost mapping page, a repeated page, 5 throttled
  answers in a row, or ids short of 90 % of `totalElements` stop the load as fatal. Kept per hotel: active ones with a
  numeric id, a name and a city. The city is Tripjack's own for the hotel's region code when it matches the city
  list (so "See all hotels" can search it), else the address city. Stars are rounded, and null outside 1-5. The mock
  lists its four templates in Goa, India.
- **Store** (`HotelCatalogService`).
  - The claim is an `INSERT … ON CONFLICT DO NOTHING` plus a conditional `UPDATE` with the load's `run_id`; a running
    load is taken over only once its heartbeat is 15 minutes old.
  - Each batch is upserted in its own transaction, retried row by row when it fails, and bumps the heartbeat; a load
    that lost its claim stops there.
  - Hotels not seen by this load are deleted only when the load looks complete (nothing fatal, at most 10 % of
    calls or rows lost, at least 90 % of the last load's count) and still holds the claim. Otherwise it ends
    FAILED, keeping the old rows and the error (cut to 300 characters).
- **Suggest.** `GET /api/hotels/suggest?q=` (owner and agent, never gated):
  - 3-60 characters, up to 8 hotels, from suppliers with hotel search on;
  - `%`, `_` and `\` are matched literally;
  - names starting with the text come first, then `word_similarity`, then stars;
  - `statement_timeout` 500 ms; a failure offers no hotels.
- **One hotel.** `HotelSearchQuery.hotelId` (namespaced) asks only that hotel's supplier; an unknown namespace or a
  supplier with search off is 400 `OFFER_UNAVAILABLE`. Tripjack lists just that id (a non-numeric one lists
  nothing). A kept search answers a later page only for the same `hotelId`. Detail, review and book are unchanged.
- **Frontend.**
  - `EntityPicker` gained `onQueryChange` and unfiltered `extraOptions` / `extraHeading` / `onExtraPick`.
  - `CityPicker` with `onHotel` lists "Hotels" for 3+ typed characters (250 ms pause, stale answers dropped) and
    shows a picked `hotel` in the field.
  - Home ("City or hotel"; recent searches keep the hotel) uses it, and so do Search Hotels (URL `hotel` /
    `hotelName`; the banner "Showing … only · See all hotels in {city}"; "No rooms at … for these dates"; a hotel no
    longer offered falls back to its city with a notice) and the lead's Search Hotels modal.

Not verified live: the country form of the mapping call and its paging, the content field names (`regioncode`,
`is_active`), and Tripjack's limits and its terms for keeping a copy. Before the first production refresh, run the
console checks: the mapping for `INDIA` (`totalElements`, `totalPages`, whether size 2000 is honoured), content for
50 ids, and pg_trgm in `public`. Tests: `HotelCatalogServiceTest`, `TripjackHotelCatalogTest`, the
`HotelSearchServiceTest` hotel cases, and `HotelCatalogIT` (Testcontainers: V3 on a fresh database; claim, prune and
takeover SQL; suggest over 100k rows); e2e `e2e/hotel-name-search.spec.ts`.

## Flight fare calendar (2026-11-18, owner rulings)

Agents saw no fares while picking a date. Every flight search that can give a comparable per-adult fare (one way, adults
only, REGULAR fare category, every supplier answered, a fresh supplier answer rather than the in-memory page cache) now
stores the cheapest published fare of an offer with a base fare, divided by the adults, in public V5
`flight_fare_calendar`, keyed by origin, destination, cabin and departure date. The upsert replaces the old row: a day
shows the least fare its newest search found, not an all-time minimum (owner ruling), and however old it is. The table is
shared by every agency and holds no tenant or personal data; `GET /api/flights/calendar` prices each day for the caller
agency (`PricingMath.displayed`, which needs only the gross fare, so the cheapest stored fare is every agency's cheapest),
for at most 62 days from India's today. A failed write is logged and never fails the search. Round-trip combined fares,
children, infants and special fares are not recorded; the Return calendar shows the reverse route's one-way fares. Past
rows are not deleted yet (filtered on read). Frontend: the search forms' one `DatePicker` (two months on wide screens, one
on phones, cheapest day in green) shows them on Departure and Return; the hotel searches use the same picker. Tests: `FlightFareCalendarServiceTest`,
`FlightFareCalendarIT`, `FlightSearchServiceTest`, `FlightSearchControllerTest`, `DatePicker.test.tsx`. Design:
`docs/superpowers/specs/2026-11-18-flight-fare-calendar-design.md`.

## Product round 2026-11-19 (owner rulings)

- **Add money: bank transfer or offline, no online.** Screens and emails say "Add money" instead of "Top up". One
  endpoint takes both kinds: a bank transfer sends its UTR (unique ignoring case and spaces) and the payment proof
  (required with a UTR; note optional), an offline payment sends no UTR and a note of 3-500 characters (checked after
  trimming; a blank UTR is stored NULL, which the UNIQUE index allows many times). `TopupResponse.hasProof` tells the
  admin screen whether to offer View proof, and the mode shows as Bank transfer or Offline by `bankRef`. Approval credits
  with "Money added (bank transfer), UTR X" or "Money added (offline)"; an online credit reads "Money added (online),
  payment ...". The online path (gateway order, simulate, webhook) stays in the API, unused by any screen, because the e2e
  suite funds wallets through it.
- **No low-balance alert.** `PUT /api/wallet/threshold`, `WalletThresholdRequest`, `WalletQueryService.setThreshold`,
  `WalletService.checkLowBalance`, `NotificationService.lowBalance` and its template are gone, and
  `WalletSummaryResponse.lowBalanceThreshold` with them. `NotificationKind.LOW_BALANCE` stays so old outbox rows still
  read. The two `wallet` columns are unmapped and drop in public V7.
- **Log in as agency.** `POST /api/platform/agencies/{id}/login-as` (SUPER_ADMIN) returns the owner's login with a token
  that lives one hour (`app.jwt.login-as-expiration-ms`) and names the admin in `actBy`; 404 for an unknown agency, 409
  `AGENCY_DEACTIVATED`; audited once as `TENANT_LOGIN_AS`. It is an ordinary owner token: the plan's gates and a
  SUSPENDED agency's read-only rule apply, and what the admin changes is recorded as the owner. Owner tokens are not
  revocable, so the short lifetime is the limit.
- **Search prices at most 2 minutes old.** The flight search result cache lives 2 minutes (was 10). A Search click already
  bypassed it; page, sort and filter changes now go back to the suppliers once the result is 2 minutes old. Only the fare
  calendar shows older fares.
- Frontend only: the flight and hotel Book buttons read "Book" with a book icon.
- Frontend only (2026-11-20): one-way flight results carry a nearby-dates strip, seven days around the searched date with the
  fare calendar's stored fare per adult (or "View"), paged a week at a time; picking a day searches it.
- **Fare types grouped by cabin (2026-11-20).** Airlines send dozens of fare names (Eco Flex, Ndc Economy Basic, Business
  Semi Flex ...), which made the fare type filter unusable. Every offer now carries `cabin` (`util/FareCabins`: Tripjack's
  `fd.ADULT.cc`, else words in the fare name, else the searched cabin; the mock uses the searched cabin), the page carries
  `fareCabins` (each cabin with how many flights of the unfiltered result have a fare in it) and the request takes
  `fareCabins`. The frontend filter lists "Economy (42)", "Business (18)" ...; the fare chips keep the airline's fare name
  (owner rulings). `fareTypes` (facet and filter) stays for old clients. Tests: `FareCabinsTest`,
  `FlightResultPagingTest#fareTypesAreGroupedByCabinWithTheFlightsOfEachAndFilterByIt`; e2e `e2e/flight-fare-cabins.spec.ts`.

Tests: `TopupServiceTest`, `TopupControllerTest`, `AuthServiceCoverageTest`, `PlatformAgencyControllerTest`,
`JwtServiceLoginAsTest`, `FlightSearchServiceTest#aCachedResultLivesTwoMinutesSoSearchPricesAreNeverStale`; e2e
`e2e/wallet.spec.ts`, `e2e/platform-login-as.spec.ts`.

## CRM improvements 2026-11-20 (client document, owner rulings)

The client's "Internal - Improvements" document asked for four CRM changes. Tenant migration `V3__crm_improvements.sql`
adds nullable columns only: `lead_service.misc_details`, `lead_service.visa_status`, `lead_proposal.visa_duration`, and on
`booking` the issue details (`assigned_by_id/_name`, `issued_by_id/_name`, `actioned_by_id/_name`, `purchase_currency`,
`selling_currency`). No CHECK covers the enums, so the new values needed no constraint change.

- **Miscellaneous service type.** `ServiceType.MISCELLANEOUS` and `BookingType.MISCELLANEOUS` (1:1 in
  `ServiceBookingTypeMapper`). A Miscellaneous service has no dates and describes itself in `miscDetails` (500
  characters; its label is "Miscellaneous", or "Miscellaneous - <first 40 characters>" beside a second one). Its
  booking is logged like the others: the voucher type is OTHER, the reference is kept as the PNR, the destination is the
  lead's, travellers are on it. It invoices in the existing Miscellaneous category (MSC series, the 18 % "other services"
  slab). Agents are given it per agent like any type; the agency settings seed Travel Insurance, Forex and SIM Card as
  its preferences.
- **Issue details (record-only).** `PATCH /api/bookings/{id}/issue-details` (owner, or an agent who can open the booking;
  CONFIRMED or COMPLETED only, else 409 `NOT_ISSUED`; accountants 403) replaces the set: three people (the owner, whose
  id is the agency id, or one of the agency's staff, else 400 "Pick someone from your agency") and two 3-letter
  currencies; blank clears. It moves no money, accounting, access or supplier state, so a Voyra booking takes it too and
  no change alert is raised; it is recorded in the booking's history. `BookingResponse.assignedByName` prefers the
  recorded person; the list's "Issued by" (`agentIs`) and "Assign by" filters match the recorded person when one is
  set. Owner and agent renames re-sync the name snapshots (`BookingRepository.updateIssueDetailNames`). The vendor is the
  booking's own (`vendor_id` / `supplier`); there is no separate vendor field.
- **Visa duration and paid clearance.** `visaDuration` (60 characters) on `VISA_FEE` proposal lines only (any other line
  is a 400 field error; on update, omitted keeps it and blank clears it), shown on the public proposal. Paid clearance is
  a frontend button that adds an ordinary priced `VISA_FEE` line ("Paid clearance - <visa line>"); the two lines are not
  linked.
- **Visa status on the Visa service.** `VisaApplicationStatus` NOT_APPLIED, APPLIED, PENDING ("Status pending"),
  CONFIRMED, REJECTED on `lead_service.visa_status`; a new Visa service starts NOT_APPLIED, an update that leaves it out
  keeps it, a stored null reads NOT_APPLIED, and any other type refuses one (400 field error). The lead status and its
  funnel are unchanged, and so is the Visa module's derived `VisaStatus`.

Tests: `MiscellaneousServiceTypeTest`, `ServiceInstanceServiceCoverageTest` (visa status, misc details),
`LeadServiceProposalAccessTest` (visa duration), `BookingServiceCoverageTest#issueDetailsAreRecordedOnlyOnceIssued...`,
`BookingControllerAccountantAuthTest#issueDetails...`, `BookingSpecificationsTest`, `InvoiceServiceCategoryTest`; e2e
`e2e/crm-improvements.spec.ts`.

## Client round 2026-11-22 (owner rulings): cancellations, boarding passes, vendor balance, visa date

Design: `docs/superpowers/specs/2026-11-22-client-round-design.md`. Tenant migrations `V4__cancellation_follow_ups.sql` and
`V5__boarding_passes.sql` (new tables only). Visa PAN and per-country checklists (step 5 of the design) are not in this
release.

- **Visa Open Case.** No application date is asked; the server stamps it (`VisaService`, `@PrePersist`) and
  `VisaResponse.createdAt` (from `Visa.createdDate`) shows when the case was opened.
- **Vendor balance.** Frontend only: the vendors list reads `GET /api/accounts/payables/ledger/vendors` on plans with
  ACCOUNTS_GST and shows the credit limit (red, with what is used) and the advance held (green); without it only the limit.
- **Cancellations tab.** `cancellation_follow_up` (SCHEDULED_CANCEL | FOLLOW_UP, due date, note, assignee, OPEN/DONE, never
  deleted) and `booking_activity` (what people did on a booking, readable by everyone who may open it). `/api/cancellations`
  (CRM_CORE; owner and agent write, accountant reads) lists, in the caller's `accessibleToAgent` scope, bookings that are
  cancelled (not fail-cancelled Voyra mirrors), have a Voyra cancel request (public table via the search path, narrowed to
  the tenant) or have a follow-up, as OPEN / DUE / ALL pages built in SQL (`CancellationSpecifications`). The status is
  worked out by `CancellationStatusRules` in the client's wording: Cancellation pending, Follow-up required (a scheduled
  cancellation due today or past), Cancellation requested / in progress / failed, Withdrawn, Partly cancelled, Refund
  pending, Refund processed, Cancelled successfully, Kept (not cancelled). Follow-ups: `POST /api/cancellations/{bookingId}/follow-ups`
  (`dueDate` or `inDays` 1-30; a scheduled cancellation needs a live booking with no request under way, a follow-up needs a
  cancellation to follow), `PATCH .../{id}` (move, reassign, note), `PATCH .../{id}/done`. Overdue ones join
  `EscalationService` as `CANCELLATION_FOLLOW_UP`. The DUE view sorts in memory (shortcut noted in the code).
- **Boarding passes.** `boarding_pass` (one row per booking and travel date once someone acts: GENERATED with an optional
  proof file, or NOT_NEEDED with an optional reason; no row = still to do). `/api/boarding-passes` (CRM_CORE; owner and
  agent): CONFIRMED flights (CONFIRMED or COMPLETED for a past day) travelling on a day, tomorrow in India by default, by
  the booking's own dates or an E_TICKET voucher's `details.journeyDate/returnDate`; bookings with a Voyra request under way
  are left out and passengers a completed request cancelled on that journey are left off (`PlatformCancelRequestService.cancelledOn`).
  Mark (multipart), undo (`DELETE`, file deleted after commit), `GET /{id}/file` (attachment, no-store); every action is
  in the booking activity.
- **Ancillary vouchers.** `BookingDocumentType.ANCILLARY` on FLIGHT bookings only: `details.ancillaryKind` (SEAT, MEAL,
  BAGGAGE, OTHER) and `ancillaryDescription`, travellers ticked from the client's members, no legs; priced, billed and
  supplier-billed like any voucher. Its invoice line reads "Seat 12A (ref) - name"; the air invoice prints a line with
  travellers but no legs as one add-on row, amount under Other.
- **Visa case owner (2026-11-23).** `POST /api/visas` no longer takes an agent from the owner: an owner's case is the
  owner's (agent id = agency id, name = owner name, kept in line by `OwnerNameSyncService`), an agent's case their own
  (still only an agent who handles visas). `VisaCreateRequest.agentId` is ignored, kept for old clients.
