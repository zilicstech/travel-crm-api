# Voyra Global Travel CRM — Backend Progress

Status as of **2026-08-13**. This document explains what has been built, how it was verified, and what's left. The architecture decisions behind all of this are recorded in [`docs/BACKEND_PLAN.md`](docs/BACKEND_PLAN.md) — the approved plan this implementation follows.

## TL;DR

All 15 build tasks are complete. The backend runs locally, is fully wired to a real PostgreSQL database, and every module has been exercised end-to-end with real HTTP requests (not just compiled) — including the two hardest correctness/security cases: multi-tenant data isolation and the public proposal link's pricing-safety guarantee. **56 endpoints** are live across auth, platform, agency, and public surfaces.

What's *not* done: no GitHub remote yet (repo is `git init`'d locally only, waiting on your access), the existing `travel-crm-main` frontend is not yet wired to call this backend, and a few things were explicitly scoped out per your earlier decisions (audit log, notifications, flight/hotel search, GCS storage).

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

166 Java files, ~8,000 lines. 16 entities, 16 repositories, 19 services, 13 controllers, 60 DTOs, 14 enums, 3 Flyway migrations (2 public-schema, 1 tenant-schema).

## Architecture, in brief

- **Multi-tenant**: one Postgres schema per Agency (`tenant_<id>`), routed by `search_path`, exactly per the blueprint's pattern. Confirmed with two real agencies during testing — an owner token from one agency gets a structural 403 trying to touch another agency's data, never a silent leak.
- **Auth**: stateless JWT, three roles (`SUPER_ADMIN`, `AGENCY_OWNER`, `AGENT`), reversible AES-256-GCM password storage (chosen specifically because Owners must be able to retrieve a generated Agent password, and Platform Admins an Agency Owner's — the one case the blueprint sanctions this for).
- **Denormalization**: every list-heavy row (bookings, invoices, visas, leads) carries a live-synced `*_name` snapshot column instead of requiring a join, and multi-value fields (lead categories, customer tags) are native Postgres arrays instead of join tables — both by design, to eliminate N+1 risk structurally rather than patch it later.
- **Money & business rules are server-side, never client-trusted**: booking profit, proposal margin %, GST, and invoice payment status are always computed in the service layer from the current data, regardless of what a client sends.
- **Public proposal link**: the one deliberately unauthenticated surface. Resolves a high-entropy token (not the lead's real ID) through a public-schema index, reads the correct tenant schema via a `REQUIRES_NEW` cross-tenant transaction, and returns a hand-built DTO that structurally cannot leak `netCost`, margin, or any other internal field — verified by asserting those keys are absent from the raw JSON, not just hidden by a UI.

## What's built, module by module

### Foundation (tasks 1–8)
Local dev environment (Java 17, Maven, Postgres 16 — all installed and verified on this machine), Maven project skeleton, public + tenant Flyway migrations, full tenant-context/search_path infrastructure, JWT security core, global exception handling (`ApiErrorResponse` envelope, consistent status-code mapping), in-memory caches with startup loaders, idempotent demo-data seeding, and a local file-storage abstraction (swappable to GCS later without touching callers).

### Agency + Agent management
- `POST/GET /api/platform/agencies`, `GET/PATCH /api/platform/agencies/{id}`, `GET /api/platform/agencies/{id}/credentials` — Platform Admin onboards an Agency; this *is* tenant provisioning (creates the schema, runs its migrations).
- `POST/GET /api/agents`, `GET/PUT/PATCH/DELETE /api/agents/{id}`, `GET /api/agents/{id}/credentials` — Owner manages their own Agents. Agent removal is blocked (409) while they still have open leads.

### Customer CRM
- `POST/GET /api/customers`, `GET/PUT /api/customers/{id}`, `GET /api/customers/lookup` — the 3-step wizard payload and the phone-lookup that powers the Add Lead wizard's auto-fill.
- `/api/customers/{id}/family-members`, `/documents`, `/family-members/{id}/documents`, `/interactions` — sub-resources, with document upload going through the storage abstraction.

### Lead + Proposal + Public link (the most complex slice)
- `POST/GET /api/leads`, `GET /api/leads/{id}`, `PATCH .../status`, `.../follow-up`, `.../assign` (Owner-only), `POST .../notes`, `POST/DELETE .../proposal-items`, `PATCH .../visa-tracker`, `POST .../proposal-link`.
- `GET /api/public/proposals/{token}`, `POST /api/public/proposals/{token}/approve` — unauthenticated, pricing-safe by construction.
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

## How this was verified

Every module above was tested with real `curl` requests against the running server and a real Postgres database — not just "it compiles." That process caught and fixed two genuine bugs:

1. **Hibernate enum-array mapping bug**: `Lead.categories` (a `List<LeadCategory>`) was missing `@Enumerated(STRING)` alongside `@JdbcTypeCode(ARRAY)`, so Postgres was storing ordinal values that couldn't be read back. Fixed, and one pre-fix test row's corrupted data was cleaned up directly in the database.
2. **Timestamp-before-flush bug**: a few `create` responses returned `null` for `createdDate`/`uploadedDate` because they read the in-memory entity before `@PrePersist` had populated it. Fixed by setting these explicitly at construction time.

Specific things explicitly confirmed, not assumed:
- Cross-tenant access is structurally blocked (tested with two real agencies).
- Agent-role list endpoints only ever return that agent's own records (fixes a real bug present in the original mock UI, where this scoping was missing).
- The public proposal JSON was asserted, field-by-field, to never contain `netCost`, `margin`, `status`, `priority`, `assignedTo`, `phone`, `email`, `budget`, or `customerId`.
- An unknown/guessed proposal token returns the same generic error as an expired one — it never reveals whether a token existed.
- Mandatory-reason rules (lost lead, cancelled booking) reject the request with a 400 when the reason is missing.
- Owner-only reassignment returns 403 for an Agent.
- GST, profit, and margin math were checked against hand-computed expected values.

## Running it locally

```bash
cd "/Users/shivanshsrivastava/Projects/Voyra global/travel-crm-backend"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
set -a; source .env; set +a
./mvnw spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

Seeded demo accounts (all password `Passw0rd!`):
- Platform Admin: `admin@travelos.com`
- Agency Owner: `owner@globalexplorer.com` (Global Explorer Travels)
- Agent: `liam@globalexplorer.com`

## Explicitly out of scope (per earlier decisions)

- Audit log and notification system.
- Flight/Hotel search integration (the mock UI's search pages stay client-side mock for now).
- GCS file storage (local disk only; the storage layer is already abstracted so this is a config flip + one new class later).
- Department-specific portals, monthly targets, and other full-BRD features beyond what the current UI needs.

## Next steps

1. **You**: send the GitHub username and SSH access when ready — the repo is `git init`'d locally with nothing pushed yet.
2. **Frontend integration**: wire `travel-crm-main` to call this backend instead of `lib/mockData.ts`. Not started — this is a frontend-side task (replacing every mock import with real `fetch` calls against the endpoints above) and hasn't been scoped in detail yet.
3. **GCS migration**: when you're ready to move off local disk, add a `GcsFileStorageService` implementing the existing `FileStorageService` interface and flip `STORAGE_PROVIDER=gcs` — no other code changes needed.
4. **Deployment**: a `Dockerfile` already exists in the repo; choosing and configuring an actual hosting target (Cloud Run, etc.) hasn't been done.
5. Anything from the "explicitly out of scope" list above, if priorities change.
