# Voyra Global Travel CRM — Backend Design Plan

## Context

Voyra Global's CRM currently exists only as a Next.js UI (`travel-crm-main/`) driven entirely by hardcoded mock data (`lib/mockData.ts`) — no auth, no persistence, most create/update buttons are unwired stubs. The BRD describes a much larger long-term vision (department-specific portals, GST/vendor accounting, audit logs, notification matrix, employee targets), but per your direction **v1 backend scope = exactly what the current UI needs to become real**, nothing from the BRD beyond that.

We're building this backend from scratch in Java Spring Boot + PostgreSQL, following `BACKEND_BLUEPRINT.md` verbatim for every architectural convention (multi-tenancy via schema-per-tenant, JWT auth, exception handling, DTO/entity/response shape, ID generation, etc.) — that blueprint is a proven pattern from a prior project (`my-fund-api`) and is to be treated as house style, not just inspiration.

Three explore passes read every route/component in the UI in full. Key findings baked into this plan:
- The mock UI has real scoping bugs (Agent-role pages read agency-wide data instead of filtering by `agentId`) — the backend must enforce correct scoping server-side regardless of what the mock did.
- The public proposal page (`/proposal/[id]`) currently keys off the raw internal Lead ID and would leak `netCost`/margin if naively backed by a full-entity API — this needs a dedicated narrow DTO and an unguessable token, not the Lead's real ID.
- "Visa" is modeled twice in the mock (a lightweight 5-step tracker embedded per-lead, and a fuller standalone `Visa` record on the owner's dedicated Visa dashboard) — both are real UI surfaces and both need backend support.
- `profit` (bookings) and `margin %` (proposal items) must be server-computed, never trusted from client input.

Decisions you've made that shape this plan:
1. **Scope**: mirror the UI only — no audit log module, no notification system, no department-specific portals, no monthly targets.
2. **Agent credentials**: owner adds an agent → backend auto-generates a password, stored with the blueprint's reversible AES-256-GCM encoder, retrievable via an admin-only endpoint (same pattern the blueprint already documents for this exact scenario).
3. **File storage**: build behind a small `FileStorageService` interface; ship a local-disk implementation now for dev/testing, swap in a GCS implementation later without touching callers.
4. **Flight/Hotel search**: out of scope entirely for v1 — no backend endpoints, no "Add to Proposal" wiring for those two pages.

## Repository

New project, sibling to the UI folder: `/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend`. Package base: `com.voyra.crm`.

**Source control**: `git init` locally as part of setup (so history exists from commit 1), but no `git remote add`/push — that's deferred until you hand over the GitHub username and SSH access. Standard Maven layout (`pom.xml` at root, `src/main/java/...`) so it opens cleanly in IntelliJ via "Open" → the `pom.xml` once it's installed; no IntelliJ-specific config needed from this end.

## Local development environment setup

Checked this machine directly: Apple Silicon (`arm64`), Homebrew 6.0.17 present (`/opt/homebrew`), Xcode CLI tools present — but **no Java, no Maven, no PostgreSQL, no Docker installed yet**. All of the below happens before any code is written, so the moment the skeleton exists you can actually run it.

1. **Java 17** (blueprint §0 pins this exact version):
   ```
   brew install openjdk@17
   sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
   ```
   Then add to `~/.zshrc`: `export JAVA_HOME=$(/usr/libexec/java_home -v17)`. Verify with `java -version` → should report `17.x`.
2. **Maven**: `brew install maven`. Verify with `mvn -version` (also confirms it's picking up Java 17). The project additionally ships a Maven Wrapper (`./mvnw`) once scaffolded, so future runs don't strictly depend on the global `mvn` install.
3. **PostgreSQL 16**:
   ```
   brew install postgresql@16
   brew services start postgresql@16
   /opt/homebrew/opt/postgresql@16/bin/createuser -s voyra
   /opt/homebrew/opt/postgresql@16/bin/createdb -O voyra voyra_crm_dev
   ```
   Verify with `psql -U voyra -d voyra_crm_dev -c '\dt'` (empty table list, but confirms connectivity).
4. **Local config**: `.env` (git-ignored, real values) generated from a committed `.env.example`, per blueprint §8.12 — `DB_URL=jdbc:postgresql://localhost:5432/voyra_crm_dev`, `DB_USERNAME=voyra`, `DB_PASSWORD=` (local trust auth, no password needed for a local-only role — fine for dev, real password required once this moves to any shared/cloud environment), `JWT_SECRET` (generate with `openssl rand -base64 32`), `PASSWORD_ENCRYPTION_KEY` (same, for the AES agent-credential encoder).
5. **Running it**: `./mvnw spring-boot:run` from the project root. On success: Flyway applies the public migration, the seed runner creates the demo agency/owner/agent (§ bootstrap step 7), and the app listens on `localhost:8080`.
6. **Visualizing it's up**: Swagger UI at `http://localhost:8080/swagger-ui/index.html` — this is the primary way to see and exercise every endpoint as it's built (login as the seeded Owner/Agent, authorize with the returned bearer token, hit endpoints, watch responses) without needing the frontend wired up yet. Wiring the actual `travel-crm-main` UI to call this backend instead of `lib/mockData.ts` is a separate follow-on task, not part of this backend build — flagging it now so it's an explicit later decision, not an assumption.

This setup happens once, up front, then stays untouched — the rest of development is `./mvnw spring-boot:run` + Swagger UI in a loop as each vertical slice lands.

## Tenancy model (Agency = Tenant)

Follows blueprint §3 exactly: shared Postgres database, one schema per Agency (`tenant_<agencyid>`), routed via `search_path`. Three principal types, all resolving a JWT `role` claim to the blueprint's `UserType` pattern:

| Role | Where the login record lives | Scope |
|---|---|---|
| `SUPER_ADMIN` | `public.platform_admin` | Cross-tenant (platform), can override tenant via `X-Tenant-Id` per blueprint §3.7 |
| `AGENCY_OWNER` | `public.tenant` (the Tenant entity itself is the owner login, exactly like blueprint §3.6 — no separate owner table) | Own tenant only |
| `AGENT` | `public.agent` (tenant_id column, same shape as blueprint §8.4's `AppUser` reference entity) | Own tenant, further scoped to own `agentId` in every list/read query |

Business/domain data (Customer, Lead, Booking, Visa, Invoices, Proposal items, etc.) lives entirely inside each tenant's schema — `agentId`/`customerId` are plain indexed varchar columns, no cross-schema FK, per blueprint §8.4's flat-FK guidance.

## Public schema entities

- `tenant` (Agency + Owner login): `id` (tenant id / schema key), `agency_name`, `owner_name`, `owner_email` (unique), `password` (AES), `is_active`, `created_date`.
- `platform_admin`: `id`, `name`, `email` (unique), `password` (AES), `is_active`, `created_date`.
- `agent`: `id`, `tenant_id`, `name`, `email` (unique), `phone`, `department` (enum, see below), `password` (AES), `is_active`, `created_date`.
- `proposal_link`: `token` (PK, high-entropy), `tenant_id`, `lead_id`, `created_date`, `expires_at` (nullable). Public-schema index enabling the unauthenticated proposal page to resolve which tenant schema to read from — this is the cross-tenant `REQUIRES_NEW` pattern from blueprint §3.5 applied to an unauthenticated read.

## Tenant schema entities

All money fields are `NUMERIC(19,2)` / `BigDecimal` (blueprint §8.4 — never `double`). All primary keys use the blueprint's `IdGenerator` (§8.5: uppercase base36, collision-checked with retry) — 6 chars for low-cardinality tables (`customer`, `lead`, `booking`, `visa`, invoices), can extend to 8 for very high-volume child tables if needed later. Every table gets `created_date` set via `@PrePersist` only-if-null, per §8.4.

- `customer`: id, agent_id, **agent_name** (denormalized snapshot, see below), name, email, country_code, phone, dob, gender, city, country, nationality, passport_number, passport_expiry, preferred_airline, preferred_cabin, status (`CUSTOMER|LEAD|VIP|CORPORATE`), **tags `TEXT[]`** (native Postgres array, not a join table), created_date.
- `customer_document`: id, customer_id, name, file_key, doc_type, uploaded_date.
- `family_member`: id, customer_id, name, relation (`SPOUSE|CHILD|PARENT|SIBLING|OTHER`), dob.
- `family_member_document`: id, family_member_id, name, file_key, doc_type, uploaded_date.
- `customer_interaction`: id, customer_id, author_agent_id, **author_name** (snapshot), note, created_date. (Fixes the mock's gap where notes had no real author.)
- `lead`: id, customer_id (nullable), name, email, country_code, phone, destination, travel_date_from, travel_date_to, budget, status (`NEW|CONTACTED|QUALIFIED|PROPOSAL_SENT|NEGOTIATING|BOOKED|LOST`), source (`WEBSITE|WHATSAPP|PHONE_CALL|SOCIAL_MEDIA|WALK_IN|REFERRAL|OTHER`), priority (`HIGH|MEDIUM|LOW`), assigned_to (agent_id), **assigned_agent_name** (snapshot), follow_up_date, lost_reason, adults, children, infants, special_requirements, public_proposal_token (unique, nullable), created_date.
  - **`categories TEXT[]`** directly on `lead` (`HOLIDAY_PACKAGE|HOTEL|FLIGHT|VISA`, multi-select) — native array instead of a join table.
  - Visa-tracker booleans as nullable columns directly on `lead` (`passport_collected`, `photos_collected`, `forms_filled`, `submitted_to_embassy`, `approved`) — only meaningful when `VISA` is in categories, matching the mock's optional `visaTracker`.
- `proposal_item`: id, lead_id, type (`FLIGHT|HOTEL|TRANSFER|ACTIVITY|VISA_FEE|MISCELLANEOUS`), description, supplier, net_cost, selling_price. (Kept as its own table, not embedded JSON — see denormalization rationale below.)
- `lead_note`: id, lead_id, author_agent_id, **author_name** (snapshot), text, created_date.
- `visa`: id, customer_id, **customer_name** (snapshot), agent_id, **agent_name** (snapshot), lead_id (nullable, for traceability), country, visa_type, passport_number, status (`DOCUMENTS_PENDING|APPOINTMENT_SCHEDULED|SUBMITTED|APPROVED|REJECTED`, persisted and kept in sync with the booleans below), passport_collected, photos_collected, forms_filled, appointment_date, biometrics_done, submitted_to_embassy, approved, rejected, passport_returned, visa_validity, expiry_date, application_date. (Standalone entity — the owner's Visa dashboard is a distinct module from the lead-embedded tracker, per the mock, which already denormalizes `customerName` here.)
- `booking`: id, customer_id, **customer_name** (snapshot), agent_id, **agent_name** (snapshot), type (`FLIGHT|HOTEL|PACKAGE|VISA`), destination, pnr, ticket_no, airline, supplier, journey_date, return_date, trip_type, net_cost, selling_price, profit (server-computed = selling_price − net_cost, stored for query performance but always recomputed on write), booking_status (`CONFIRMED|PENDING|CANCELLED|COMPLETED`), payment_status (`PAID|PARTIAL|PENDING|REFUNDED`), booking_date, cancel_reason, refund_status.
- `client_invoice`: id, customer_id, **customer_name** (snapshot), agent_id, amount, gst, total_with_gst, amount_paid, status (`PAID|PARTIAL|PENDING`), invoice_date, due_date, payment_mode.
- `supplier_invoice`: id, supplier_name (already a plain string, no supplier entity — matches the mock), category (`FLIGHT|HOTEL|PACKAGE|VISA`), amount, status (`PAID|PENDING`), due_date, booking_ref (booking_id).

`AgentDepartment` enum: `SALES|OPERATIONS|VISA|HOLIDAY_PACKAGES|ACCOUNTS` (BRD's full department list — it's just a categorization value on `agent`, not a role, so keeping the full set costs nothing now vs. revisiting later).

## Denormalization strategy (avoiding N+1)

True JPA N+1 comes from lazy `@OneToMany`/`@ManyToOne` associations traversed in a loop — the blueprint already structurally rules this out (§8.4: "prefer flat foreign-key columns over JPA associations," its own reference migration doesn't even declare a `FOREIGN KEY` constraint, just an indexed flat column). This plan follows that as-is: **no JPA associations anywhere, only flat id columns.** On top of that baseline, two further moves target the specific N+1-shaped risk of *list/dashboard screens needing a related display name per row*:

1. **Snapshot name columns** (`customer_name`, `agent_name`, `assigned_agent_name`, `author_name`) on every row that a list view renders alongside a related name — `booking`, `client_invoice`, `visa`, `lead`, `customer_interaction`, `lead_note`. This is the same pattern already present in the mock data itself (`initialClientInvoices` and `initialVisas` both hardcode `customerName` inline rather than joining) — this plan just applies it consistently everywhere a list screen needs it, so every list endpoint is a single flat `SELECT ... WHERE agent_id = ?` with zero joins.
   - **Sync policy**: these are *live-synced*, not point-in-time snapshots. Renaming an Agent (or Customer) runs a bulk `UPDATE` of every denormalized `*_name` column referencing that id, in the same service method/transaction as the rename — the same rule the blueprint already applies to its static caches (§8.10 rule 2: "every write path that changes the cached state must update the cache in the same method as the DB write"). Renames are rare (agent/customer profile edits), so this bulk update is cheap and keeps every list screen correct without ever needing a join.
2. **Native Postgres arrays** (`TEXT[]`) for the two multi-value fields (`lead.categories`, `customer.tags`) instead of join tables — a join table here would force either an N+1 fetch-per-parent or an aggregate join on every list read; an array column is a single scalar column, filterable with `= ANY(categories)`, and needs no join ever.

**What's deliberately left as a normal child table** (not embedded/denormalized): `proposal_item`, `lead_note`, `customer_document`, `family_member` (+ its documents), `customer_interaction`. These are only ever loaded in the context of **one single parent record** (one lead's detail page, one customer's profile page) — never in a bulk list of many parents — so there is no N+1 risk to begin with: it's always exactly one extra query (or one JOIN) per page view, not one per row. Embedding them as JSON columns would deviate from the blueprint's entity-per-Flyway-table pattern for no real benefit, and would sacrifice independent queryability (e.g. a future "documents expiring soon" report) for nothing gained.

## Blueprint-compliance checklist

Explicitly verified against `BACKEND_BLUEPRINT.md` while designing the above:

- [x] Multi-tenancy: schema-per-tenant, `search_path`-routed, `TenantContext` ThreadLocal, no manual `tenantId` filters on tenant-schema queries (§3) — the public-schema `proposal_link` table is the one place a manual `tenant_id` filter is correct, per §3.5's explicit exception for `public`-schema queries.
- [x] Entities: flat FK columns, no JPA associations, `@Enumerated(EnumType.STRING)` for every enum, `BigDecimal`/`NUMERIC(19,2)` for money, `IdGenerator`-style collision-checked IDs (§8.4–8.5).
- [x] Auth: stateless JWT, `AesPasswordEncoder` used *only* because this app has the same "admin must retrieve a generated credential" requirement the blueprint calls out as the sole justification for it (§4.7) — Owner retrieving an Agent's password.
- [x] Authorization: `@PreAuthorize` on every non-auth endpoint, audience-split controllers (`Owner*Controller` vs `Agent*Controller`) where scope differs, business-rule authorization (agent-removal-with-open-leads block) thrown as `IllegalStateException`/`AccessDeniedException` in the service layer, not the controller (§5).
- [x] Cross-tenant work: the public proposal flow is the one place this app needs it, and it follows §3.5's `REQUIRES_NEW` + try/finally pattern exactly, on a dedicated bean (not self-invocation).
- [x] Exceptions/responses: one `GlobalExceptionHandler`, DTOs only (never entities) out of controllers, generic `ApiErrorResponse` envelope (§6–7).
- [x] Reports/aggregates: implemented as repository **projection interfaces with explicit `@Query` joins** (§8.1's stated purpose for projections) — this is the one legitimate place real joins/aggregation happen, and it's a single `GROUP BY` query per report, not a per-row loop, so it isn't the N+1 pattern being avoided above.

## Security

Straight application of blueprint §4–5:
- `UserType`: `SUPER_ADMIN, AGENCY_OWNER, AGENT`.
- Three login endpoints under `/api/auth/**` (only permit-all surface): `/login/platform-admin`, `/login/owner`, `/login/agent`.
- JWT claims: `sub`, `userId`, `role`, `tenantId` (omitted for `SUPER_ADMIN`), no `scopeId` needed for this domain.
- Password encoder: blueprint's `AesPasswordEncoder` (reversible), since the Owner must retrieve an Agent's generated password. `decode()` reachable only from an `AGENCY_OWNER`-guarded, same-tenant-checked endpoint (`GET /api/agents/{id}/credentials`), logged on every retrieval.
- Every controller method carries `@PreAuthorize`, no exceptions outside `/api/auth/**`, per blueprint §5.3.
- **Controllers split by audience** where data scope differs (blueprint §5.3 rule): `Owner*Controller` (`hasRole('AGENCY_OWNER')`, agency-wide reads) vs `Agent*Controller` (`hasRole('AGENT')`, service layer filters every query by `principal.userId()` as `agentId` — this is the fix for the mock UI's scoping bug, enforced here regardless of what any future frontend trusts). Shared creation actions (Add Lead, Add Customer — same modal component on both sides in the UI) live in one `LeadController` / `CustomerController` with `hasAnyRole('AGENCY_OWNER','AGENT')`, where the service sets `assignedTo`/`agentId` to the caller when the caller is an `AGENT`, or to the specified target when the caller is an `AGENCY_OWNER`.
- Delete permissions: only `AGENCY_OWNER` can remove an Agent (matches the UI — no such action exists anywhere in the Agent-role screens); removing an agent requires reassigning or blocking on open leads (service-layer check, `IllegalStateException` → 409 if the agent still has non-terminal leads/bookings assigned).

## Public proposal flow (security-critical)

1. Owner or Agent, authenticated, on a lead detail page: `POST /api/leads/{id}/proposal-link` → generates `public_proposal_token` (blueprint `IdGenerator`-style random token, retried-on-collision) if not already set, inserts a row into public-schema `proposal_link` (token → tenantId + leadId), returns the full shareable URL.
2. Customer opens `GET /api/public/proposals/{token}` — **unauthenticated**, no `X-Tenant-Id`, no JWT. Resolves `proposal_link` in `public` schema first (tenant unknown until this lookup), then does a `REQUIRES_NEW` tenant-scoped read (blueprint §3.5 pattern, `try { TenantContext.setTenantId } finally { restore }`) into the resolved tenant schema to fetch the Lead + its `proposal_item`s.
3. Response is a dedicated `PublicProposalResponse` DTO — **only** `name, destination, travelDateFrom, travelDateTo, guestCount, items[{type, description, supplier, sellingPrice}], grandTotal`. `netCost`, margin, `status`, `priority`, `source`, `assignedTo`, `notes`, `visaTracker`, `phone`, `email`, `budget`, `lostReason`, `customerId` are never serialized into this DTO — it is a hand-built response class, not a mapped entity, so there's no risk of a field leaking by accident.
4. Unknown/expired token → generic "not found" (never reveal whether a token ever existed).
5. `POST /api/public/proposals/{token}/approve` — same resolution path, transitions `lead.status` to `NEGOTIATING` (idempotent if already past that stage), returns only `{success: true}`.

## File storage

`FileStorageService` interface (`store(tenantId, category, ownerId, file) -> fileKey`, `retrieve(fileKey) -> stream/URL`, `delete(fileKey)`). `LocalFileStorageService` implementation now, storing under `./uploads/tenant_<id>/<category>/<ownerId>/...` with the DB only ever holding the returned `fileKey` + original filename + content type — never a raw filesystem path baked into business logic. `app.storage.provider=local` config flag now; adding a `GcsFileStorageService` later is a new class + one property flip, no caller changes. Used by: `customer_document`, `family_member_document`, and package-booking voucher upload (BRD/mock's "Voucher Upload" on Holiday Package — covered by the same `booking` document hook if/when that UI lands; not building a separate voucher UI now since it's not in the current screens).

## Bootstrap order (blueprint §9, adapted)

1. Skeleton — `pom.xml` (blueprint §0 deps), `VoyraCrmApplication`, package folders.
2. Config — `application.properties`, `.env.example`, `.gitignore`, `Dockerfile`.
3. Public migration `V1__init_public_schema.sql` — `tenant`, `platform_admin`, `agent`, `proposal_link`.
4. Tenant infra — `TenantContext`, `TenantSchemaUtil`, `TenantSearchPathUtil`, `TenantSchemaDataSource`, `TenantDataSourceConfig`, `TenantFlywayMigrator`, plus tenant `V1__init_tenant_schema.sql` covering every table in the "Tenant schema entities" section above (one logical migration per table is fine here since they're all part of the same initial bootstrap — indexes on every `agent_id`/`customer_id`/`lead_id`/`status`/`follow_up_date` column used in list filters, per blueprint §2.5).
5. Security core — `UserType`, `CustomUserPrincipal`, `JwtService`, `AesPasswordEncoder`, `SecurityConfig`, `JwtAuthenticationFilter`, `SecurityContextUtil`.
6. Cross-cutting — `ApiErrorResponse`, `DuplicateConstraintMessageParser`, `GlobalExceptionHandler`, `OpenApiConfig` (bearer auth scheme).
7. Caches/runners — `TenantCache` + loader, a seed `ApplicationRunner` creating one demo agency + owner + agent (idempotent, matches mock's "Global Explorer Travels / John Davis / Liam Smith" so the existing UI can be pointed at real data with minimal friction), `TenantMigrationStartupRunner`.
8. Auth endpoints — verify end-to-end: login (all 3 roles) → token → authenticated call → correct tenant schema hit.
9. File storage — `FileStorageService` interface + `LocalFileStorageService`.
10. Domain features, one vertical slice at a time (migration → entity → repository → DTOs → service → controller, definition-of-done per blueprint §9):
    a. Agency/Tenant lifecycle (Platform: create/list/deactivate agency) + Agent management (Owner: add/list/detail/deactivate/remove agent, credential retrieval).
    b. Customer + FamilyMember + Documents + Interactions (phone-lookup endpoint powering the Add Lead wizard's "customer auto-lookup").
    c. Lead (CRUD, status/follow-up/assignment updates, notes) + Proposal builder (line items, server-computed margin) + embedded visa-tracker toggles + public proposal link/view/approve.
    d. Booking (CRUD, server-computed profit).
    e. Client & Supplier Invoices.
    f. Standalone Visa module (owner's Visa dashboard).
    g. Dashboard aggregate endpoints — Owner (agency-wide KPIs from `app/agency/page.tsx`) and Agent (own-scoped KPIs from `app/agents/page.tsx`), each as GROUP BY / aggregate repository queries, not client-side math.
    h. Reports — revenue time series, agent leaderboard, lead-source pie, booking-type pie, conversion funnel, CSV export per report.

## Verification

- Each vertical slice: `mvn spring-boot:run`, exercise via Swagger UI (`/swagger-ui.html`) — login as each of the 3 roles, confirm tenant isolation by creating two demo agencies and verifying agency A's owner token can never read agency B's leads/customers/bookings even by guessing IDs.
- Explicitly test the Agent-role scoping fix: confirm `GET` lead/customer/booking lists as an Agent only ever return rows where `assignedTo`/`agentId` equals that agent's own ID, even though the current mock UI doesn't enforce this.
- Explicitly test the public proposal endpoint: confirm `netCost` and every excluded field are structurally absent from the JSON response (not just hidden by the frontend), and that an unknown/guessed token returns the generic not-found response.
- Confirm agent removal is blocked (409) while that agent still has non-terminal leads/bookings, and succeeds once reassigned/closed.

---

## Addendum — Remediation (2026-08-13)

This plan's "Blueprint-compliance checklist" was written with `[x]` boxes before the code
existed. They recorded design intent, not verification, and one item ("Reports/aggregates:
... a single GROUP BY query per report") was not true of the delivered code.

Compliance is now machine-verified by `scripts/blueprint-audit.sh`, which runs in `mvn verify`.
Treat the checklist above as historical intent; treat the script's output as the current truth.

Gaps this plan did not cover, now addressed in `docs/REMEDIATION_PLAN.md`:

- **Testing strategy.** The original "Verification" section specified manual Swagger/curl passes
  only, reproducing the one deviation blueprint §10 explicitly names. Now a ~63-test suite.
- **Pagination.** Every list endpoint was unbounded. Now opt-in `?page=&size=`, non-breaking.
- **Deployment.** Only a Dockerfile was mentioned. See `docs/DEPLOYMENT.md`.
- **Seed-data safety.** The seed runner was unguarded and logged credentials. Now flag-gated.
- **Auth lifecycle.** 24h JWT with no refresh or logout was an unstated omission; it is now a
  recorded decision in `docs/DEPLOYMENT.md`.
