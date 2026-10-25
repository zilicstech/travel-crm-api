# B2B Platform — Slice 6: Launch hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the platform safe to switch on for real money and real tickets: platform P&L with Tripjack statement import, real email notifications, a guarded UAT→PROD switch, live-gateway readiness, the platform dashboard tiles, the DPDP purge, the supplier-readiness fixes collected in slices 3–4 (the "before enabling tripjack" checklist), and the slice 1 leftovers (suspended-agency banner, second platform admin screen, CAPTCHA on /apply).

**Architecture:** No new money paths. Additive services and screens over the existing tables (`platform_booking`, `wallet_entry`, `gateway_order`, `subscription_invoice`), a `platform_statement_import` table for Tripjack statements, an `EmailNotificationService` behind the existing `NotificationService` interface (sent after commit via `TransactionSynchronization`), and guards on the credential/provider switch.

**Tech Stack:** as earlier slices; `spring-boot-starter-mail` (new dependency — the one allowed addition, for SMTP); Cloudflare Turnstile or hCaptcha verify endpoint via `RestClient` (no SDK).

**Spec:** `/Users/shivanshsrivastava/Projects/Voyra global/B2B_PLATFORM_DESIGN.md` §11 item 6, §9 (P&L screen, dashboard tiles), §3 (DPDP), §7.1 (CAPTCHA, emails); slice 2–4 plans' rulings sections; the "Before enabling FLIGHT_BOOKING_PROVIDER=tripjack" checklist in `pr-descriptions/b2b-01-foundation-backend-PR.md`.

**Deviations (rulings):**
- The Tripjack statement import accepts a CSV with columns `booking_id, pnr, type (SALE|REFUND), amount, commission, date` (the real format is confirmed in slice 0; the importer maps columns by header name so a renamed column is a config change, not a release).
- Real Tripjack fixtures still don't exist; the supplier-readiness fixes are defensive behaviour changes with tests on the current guessed shapes.
- CAPTCHA provider: Cloudflare Turnstile (free, no tracking), keys from env `TURNSTILE_SITE_KEY`/`TURNSTILE_SECRET`; when unset, verification is skipped with a startup WARN (same pattern as the payment stub).

## Global Constraints
- All earlier Global Constraints hold. Next free public migration after slice 5's.
- Emails never block or roll back a business transaction: queued with `TransactionSynchronization.afterCommit`; failures logged and retried by a small outbox job.
- No secret ever logged; SMTP creds from env (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM`).
- PROD switch: changing the active Tripjack credential's environment to PROD, or setting `FLIGHT_BOOKING_PROVIDER`/`HOTEL_BOOKING_PROVIDER=tripjack`, is refused at startup/at save unless platform setting `tripjack_live_ready = true`, which only a second platform admin can set (second-approver rule) and which is audited.

## Review Focus
1. An email for a wallet credit sent although the transaction rolled back — must not happen (after-commit only). Task 1.
2. Statement import run twice with the same file — no double reconciliation. Task 2.
3. Setting PROD while bookings are in flight, or without `tripjack_live_ready` — refused. Task 3.
4. DPDP purge deleting passenger data of a booking still open — must not happen (only terminal bookings older than the retention period). Task 4.
5. Turnstile down — `/apply` must still work (fail-open with an alert) or fail-closed? Ruling: fail-closed for create (spam is the risk), with a clear "try again" message; uploads already rate-limited. Task 5.

---

### Task 1: Email notifications with an outbox
- `notification_outbox` table; `EmailNotificationService implements NotificationService` (low-balance → agency owner; platform alerts → platform admins; top-up approved/rejected; application decisions incl. the MORE_INFO resume link; subscription invoices + expiry reminders; booking ticketed/failed/cancelled to the booker); after-commit enqueue; `NotificationOutboxJob` sends with retry/backoff; `LoggingNotificationService` stays when SMTP is unset.
- Plain HTML templates (UI_BLUEPRINT colours), no tracking pixels.
- Tests: after-commit only (rolled-back tx sends nothing), retry, template rendering, recipients.

### Task 2: Platform P&L and Tripjack statement import
- `platform_statement_import` + `platform_statement_line` tables; `POST /api/platform/statements` (CSV upload, header-mapped, idempotent by file hash and by `booking_id+type`), matching to `platform_booking` by supplier booking id / PNR; sets `voyra_earned_actual` and `voyra_earned_source = STATEMENT`, re-derives `voyra_net`; unmatched lines listed.
- `GET /api/platform/pnl?from&to` — GMV, bookings, discounts given, commission paid, TDS withheld, Voyra earned (estimate vs statement), Voyra net, cancellations; per agency and per product.
- Frontend `/platform/pnl` (filters, tiles, per-agency table, import screen with unmatched lines).
- Tests: Review Focus 2; matching; re-derivation; P&L arithmetic.

### Task 3: Supplier readiness and the guarded live switch
- The PR checklist items: map `supplierMessage` to a fixed set of agency-safe messages (raw text admin-only); refuse one-word passenger names (first + last required, as airlines need) in the wizard and API; check each passenger's derived pax type against its slot (DOB vs travel date, existing `PaxTypeCalculator`); hotel base-rate field allow-list (documented names only, else null); alerts after commit (Task 1); Tripjack amendment treated as async (cancel accepted → CANCELLING until booking-details confirms cancelled).
- `tripjack_live_ready` setting (second-approver, audited); startup/save guards for PROD credential and tripjack providers; connection pool sized from env `DB_POOL_SIZE` (default 10) with a note in deploy docs.
- Tests: Review Focus 3; each readiness item.

### Task 4: DPDP purge and data protection
- `PassengerDataPurgeJob`: for platform bookings in a terminal state (TICKETED with travel date + 90 days passed, CANCELLED/FAILED/ABORTED + 90 days), null `pax_enc` and redact events' remaining personal fields; keep money rows. Retention days in a platform setting.
- KYC purge (slice 1) extended to approved applications' staging files.
- Tests: Review Focus 4; open bookings untouched.

### Task 5: Slice 1 leftovers and platform dashboard
- CAPTCHA (Turnstile) on `/apply` create + verify server-side (Review Focus 5).
- Suspended-agency read-only banner in every agency shell (status from login / 423 responses).
- Platform admins screen: list admins, add a second admin (generated password shown once, like owner creation), deactivate; at least one active admin enforced; audited.
- Platform dashboard tiles: GMV (30 d), Voyra net (30 d), float (Σ wallet balances), pending KYC, pending top-ups, UNKNOWN/CANCELLING bookings, PAST_DUE subscriptions.
- Tests + Chrome.

### Task 6: Full regression and docs
- `regression-tester` in **full** mode over the whole branch (all slices), fix loop until CLEAN; invariants; Playwright full suite; docs (root + FE CLAUDE.md, PROGRESS.md, PR texts: final deploy runbook — env vars, migrations, flyway repair note, Razorpay dashboard settings, Turnstile, SMTP, tripjack live steps).
