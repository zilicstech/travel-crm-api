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

## Rulings made during execution (2026-10-26)

- Ruling (carried to T4): DPDP purge also clears notification_outbox bodies of FAILED rows older than 7 days (resume-link tokens) — cost: none
- Ruling (carried to T6): deploy runbook lists SMTP_*, MAIL_FROM, APP_BASE_URL; startup fails if SMTP on and MAIL_FROM missing
- Ruling: scoped re-review folded into slice final review — cost: one pass later
- Ruling: lock-first by id; per-import negative-net alert; PNR fallback only when booking_id blank; matching restricted to the import's environment (default PROD, admin may pick UAT/MOCK for testing); P&L defaults PROD
- Ruling: voyra_earned stays the estimate; statement sets voyra_earned_actual + source=STATEMENT; net uses actual when present; P&L shows estimate vs actual side by side — spec §6 keeps both columns; §5.1 "overwritten" read as the figure in force — cost: none
- Ruling: park (bank import out of slice scope, existing behaviour) — cost: quoted-newline bank CSVs fail, as today
- Ruling: live-switch request/approve/withdraw UI + reference_fare_basket_hotel setting UI → Task 5 (platform admin screens) — API-only meanwhile — cost: none
- Ruling: startup guard blocks tripjack provider even on UAT until tripjack_live_ready (second approver) — conservative; slice 0 spike needs 2 admins (Task 5 adds admins screen) — cost: spike friction
- Ruling: CANCELLING never confirmed surfaces via Task 5 dashboard UNKNOWN/CANCELLING tile; no extra alert — cost: relies on admin looking
- Ruling: ASCII-letter names kept (GDS/airline name fields are ASCII) — cost: accented names must be typed transliterated
- Ruling: purge scope = platform copies only (tenant CRM mirror is the agency's data), + outbox bodies >7d, + approved KYC staging files; pax_purged_at column V29 — cost: none
- Ruling: fix round 1 = Important 1 (stamp mix at review in pricing_snapshot/itinerary JSON, 400 PAX_MIX_CHANGED before DOB check) + minors 1,2 (alert once after 24h CANCELLING unconfirmed — supersedes earlier dashboard-only ruling, cheap),3,4,5 (admit() refuses when active credential PROD and flag false) — money/safety, small — cost: one round
- Ruling: minor 6 → slice 0 checklist in PR text (Task 6 docs); minor 7 → Task 5 (switch UI, hotel basket UI, supplierMessageRaw type) — cost: none
- Ruling: approved-application KYC files are NOT purged (approval copies no file, so they are the only KYC evidence; delete irreversible; retention for approved KYC waits for owner/counsel) — implementer resumed to revert that part — cost: KYC files kept longer than DPDP minimum until owner decides
- Ruling: TICKETED with unreadable travel date = ticketed + 365 d before retention clock — conservative — cost: data kept longer
- Ruling: platform_audit_log admin notes untouched (audit integrity) — cost: free text may hold names; admins told in runbook to keep notes free of personal data
- Ruling: mirror PENDING/FAILED skip + substring key redaction + clearOldBodies test → slice final fix wave (small, no money) — cost: none until then (no prod data yet)
- Ruling: Chrome for Task 5 screens done in Task 6 full regression (needs backend restart; one restart there) — cost: UI bugs found later
- Ruling: kill switch must hold at confirm too (point is to stop live spend now) — fix round 2/5: in hold() after REVIEWING check refuse LIVE_BOOKINGS_PAUSED when row env PROD and !tripjackLiveReady, before hold; test. Queued until Task 5 implementer finishes (shared working tree) — cost: none (no prod traffic)
- Ruling: new SecureRandom generator in PlatformAdminService ok (none existed); per-instance admin cache accepted like tenant cache — runbook: Cloud Run max instances note — cost: deactivated admin token lives on other instances until reload; pending top-ups tile = bank only (gateway orders self-complete) — cost: none; TURNSTILE_SECRET + NEXT_PUBLIC_TURNSTILE_SITE_KEY set together → runbook
- Ruling: round-2 scoped re-review folded into slice final review (one-check, test-first fix) — named risk for final reviewer: check placement before idempotency lookup doesn't break a replayed confirm of an already-held row — cost: one pass later
- Ruling: minors 1 (cache afterCommit) + 4 (remoteip: CF-Connecting-IP else omit) → slice final fix wave; 2 (pin Cloud Run max-instances=1 or note) + 3 (hostname allow-list once domain fixed) → runbook; 5 admin reset password → follow-up backlog; 6 cosmetic park — cost: small
- Ruling: slice final review before Task 6 (regression must test final code; docs after fixes) — final reviewer dispatched (opus) on BE 45bc430..b2926bd + FE 09dbeb9..fadea03 with carried fix-wave list
- Ruling: Important 1 fixed as reviewer proposes (registry.serves(b); poller skips mismatched row + one alert; cancel/cancelQuote 409 agency-safe; startup guard allows tripjack with flag off while non-MOCK rows HELD/BOOKING/UNKNOWN/CANCELLING, message never says unset then; new live still blocked by requireLiveAllowed) — cost: none
- Ruling: Important 2 — controller rules (no owner wait): approve refused when audit log shows PLATFORM_ADMIN_CREATE linking approver and requester either way, or approver created after the request; same check on any other second-approver rule (slice 1 pricing) if one exists; owner told and may relax — cost: two genuinely separate admins needed (intended)
- Ruling: minors 3,4 (drop file-hash refusal, per-line idempotency + DUPLICATE status protect; Review Focus 2 still holds),6,7,8,9 + all carried items in the one fix wave; 5 → runbook — cost: one dispatch
- Ruling: approver rule also blocks creation chains — keep (safe); consequence: seeded admin cannot pair with admins it created, so go-live needs two admins neither created by the other; creator holds generated passwords so in-app rule cannot stop a determined single operator — runbook + owner told; forced first-login password change → backlog — cost: go-live friction
- Ruling: MOCK rows can't be cancelled once provider is tripjack (409 + alert) → runbook pre-go-live step: settle/cancel test bookings first; mismatched BOOKING rows not polled, SUPPLIER_MISMATCH alert only signal — accepted — cost: admin cleanup
- Ruling: SecondApproverRule creation-chain independence applies to tripjack_live_ready only; agency pricing approval goes back to slice 1 rule (approver ≠ requester) — the seeded admin creates every admin, so the chain rule would make the seeded admin's routine loss-making rates unapprovable forever; live switch is a one-time event where the friction is acceptable — cost: pricing sock-puppet approval possible (audited); owner told
- Ruling: E1 fix = stamp travel date (first departure / check-in) at review from the review request, use it for the pax check; mock stores an itinerary; wizard checks DOB vs slot client-side — fix wave after all testers report (rebuild would disrupt running testers)
- Ruling: pre-existing accounting design (refund owed to client), not a slice regression — park; mention to owner — cost: none now
- Ruling: A's untested items + all X1 gaps go to the re-test round after the fix wave (new tester, iframe-free 390 check via Playwright viewport screenshots) — cost: one more pass
- Ruling: regression fix wave R1-R22 per regression-fix-brief.md (A2 markup >100% not a bug; validation-before-role parked outside /api/platform/**; proposal link localhost = config; manage card order parked) — dispatched implementer (opus, both repos)
- Ruling: R3 note now required for "Request more info" (template already had it; blank notes were the cause) — accept; R11 voucher add/edit always sets total = sum of live vouchers — accept (matches invariant); R1 real supplier with no readable date + API review without date still skips age check — accept, slice 0 checklist; docs edits for R2 in root CLAUDE.md + PR text (outside git) accepted.
- Ruling: markup percent capped at 1000 (above 100 legitimate, unbounded not) in next small batch; MORE_INFO reject — investigate, admins should be able to reject an application awaiting more info unless slice 1 design says otherwise
- Ruling: small batch S1 markup cap 1000%; S2 MORE_INFO reject (investigate/allow); S3 banking + agency dashboard content width at 390; S4 marker copy; S5 HOTEL status label "Confirmed" not "Ticketed" (label only, status enum unchanged). Table-in-scroll-card row buttons accepted mobile pattern (park). Confirm in bank 390 → switch tester using B's hotel 2284 (supplier refund expected).
- Ruling: NOT-DRIVABLE counts as untested — add mock switches cancel-timeout / cancel-fail (flight + hotel, same pattern as book-timeout/book-fail) so PB11 and LH8 cancel run through the UI; then drive them — cost: small code in mock only
- Ruling: fix T1 (answer the timeout as accepted-pending: agency sees "We're confirming with the supplier", not an error) and T2 (audit records requested and posted refund; admin modal warns when typed refund exceeds the cap) — small batch, then final gates round 4
- Ruling: full regression of slices 1-6 CLEAN — report qa-reports/2026-10-26-feat-b2b-platform-full.md; final gates verify 1,735 (incl. ITs), Playwright 178, invariants hold
- Ruling (code review 2026-10-26): whole-branch review of slices 1-6 found 1 critical (tenant status cache per instance) and 15 major issues; all fixed except B2 (suspend releasing HELD rows: no code, the poller aborts a HELD row after 10 minutes and admit/hold refuse a suspended agency). Report and per-fix rulings: qa-reports/2026-10-26-feat-b2b-platform-code-review.md
- Ruling (owner, 2026-10-26): agency pricing rates need no second approver; one platform admin sets a rate and it applies at once, accountability is the PRICING_CREATE audit row (who, every figure, lossMaking). The two-admin rule stays only on tripjack_live_ready.
