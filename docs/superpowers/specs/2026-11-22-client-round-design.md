# Client round 2026-11-22: cancellations, boarding passes, vendor balance, visa — plan

## Context

The client sent a list: a cancellation tab with reminders, vendor balance, visa PAN and per-country checklists, and
boarding pass sharing with ancillaries. The owner added what was agreed on the call:
- a Cancellations tab where agents see every cancellation and its live status;
- cancellation reminders on the dashboard;
- a dashboard block for tomorrow's flights, where the agent marks each boarding pass as generated (optional proof) or
  not needed, showing the top 5 plus "See all", with every action tracked;
- the visa application date removed from Open Case, defaulting to when the case is opened.

**Owner rulings (2026-11-22):**
- **Cancellations: track plus follow-ups.** One list of every cancellation, with live status and refund progress, a
  follow-up date and note per row, and "Schedule cancellation" on a live booking. Due and overdue items appear on the
  dashboard, and history is kept. Status is worked out from the real booking, Voyra request and refund state. It is
  not the client's 8 statuses set by hand.
- **Also in this round:** vendor balance and credit limit, visa PAN plus a per-country checklist, and boarding pass
  sharing plus ancillaries.
- **Sharing a boarding pass is download only.** There is no email and no WhatsApp; the WhatsApp API stays parked on
  `feat/whatsapp-tickets`.
- **Ancillaries are recorded paid add-ons** (seat, meal, baggage, other), with purchase price plus markup, invoiced like
  a voucher. The agent buys them outside the CRM and records them here.
- **Visa:** PAN goes on the traveller (member), and the document checklist per country is set in Settings.
- **Boarding pass "not needed":** the reason is optional.

**Corrections from the code review (2026-11-22)** are folded in below. The main ones:
- Return flights can live only on a voucher.
- Agent scope differs between CRM bookings and Voyra requests.
- The vendor balance data already exists.
- Migrations get one version per step.
- The owner lands on `/agency/home`, not the dashboard.

## Delivery order

Each step is a separate commit, with the gates green each time, and each step that needs one gets its own tenant
migration.

1. **Visa application date.** No migration.
2. **Vendor balance.** Frontend only.
3. **Cancellations.** Tenant `V4__cancellation_follow_ups.sql`.
4. **Boarding passes and ancillaries.** Tenant `V5__boarding_passes.sql`.
5. **Visa PAN and country checklists.** Tenant `V6__visa_pan_and_checklists.sql`.

Public `V7` stays reserved for the drops, and this round adds no public migration.

## 1. Visa: no application date on Open Case

- **Frontend:** remove the Application date field from both Open Case modals (`app/agency/visa/page.tsx`,
  `app/agents/visa/page.tsx`). The "Submission Date" column becomes "Opened", showing date and time (IST). Update the
  `test/fe-7/visas.ts` fixture.
- **Backend:** add `createdAt` to `VisaResponse` from `Visa.createdDate` (`entity/Visa.java:105`). The server already
  defaults `application_date` to today (`VisaService:68`, `@PrePersist`; the JVM zone is IST), so there is no other
  change.

## 2. Vendor balance and credit limit in the vendors list

The change is frontend only, in `app/agency/vendors/page.tsx`:
- **Owner on a plan with ACCOUNTS_GST:** call the existing `listVendorSummaries()` (`lib/api/services/supplierLedger.ts:20`,
  `GET /api/accounts/payables/ledger/vendors`, `summaryForAllVendors`) once.
- **Each vendor card shows:**
  - **Credit limit** in red: the limit, plus "used ₹X", which is the payable, when a limit is set;
  - **Active balance** in green: the advance held with the vendor;
  - a **Statement** link to `/accounts/ledger?party=SUPPLIER&id=<vendorId>`.
- **Without ACCOUNTS_GST (Lite):** bills never reach the ledger, so the card shows only the credit limit from
  `VendorResponse` and adds no balance.
- **Agents:** they see neither the limit nor the balance, as today.
- **Voyra Platform vendor:** it shows no balance, because the wallet is not a supplier advance.

## 3. Cancellations tab, follow-ups and dashboard reminders

**Data (tenant V4):**
- `cancellation_follow_up`, modelled on `lead_follow_up` and reusing the `FollowUpStatus` OPEN/DONE enum. Columns:
  `id`, `booking_id`, `kind` (`SCHEDULED_CANCEL` | `FOLLOW_UP`), `due_date`, `note` (≤500),
  `assigned_agent_id` / `name`, `status`, `created_by` / `name`, `created_at`, `done_by` / `name`, `done_at`.
- `booking_activity`, also used by step 4. This is the "history" agents can read (`AuditService` history is owner-only).
  Columns: `id`, `booking_id`, `kind`, `text` (≤500), `by_id`, `by_name`, `at`.

**Status per booking:** worked out in one place, `CancellationStatus` with a table test, using the client's wording.
Voyra requests are read through `PlatformCancelRequestRepository` plus the agency-safe `views(...)` (never Voyra's fee),
newest request first.
- **Live booking with an OPEN `SCHEDULED_CANCEL`:** **Cancellation pending** until the due date, then
  **Follow-up required** once it is past.
- **Newest Voyra request:**
  - OPEN gives **Cancellation requested**;
  - IN_PROGRESS or COMPLETING gives **Cancellation in progress**;
  - REJECTED gives **Cancellation failed**;
  - WITHDRAWN gives **Withdrawn**;
  - COMPLETED on a still-live booking gives **Partly cancelled**, with that request's refund.
- **CANCELLED booking:**
  - Refund open, judged by the `refundOpen` logic of `BookingSpecifications.moneyPending` (`:38-48`): **Refund pending**.
  - Nothing owed on either side: **Refund processed**. Cross-check pending stays a small note, since it is Accounts'
    step.
  - Nothing to refund: **Cancelled successfully**.
  - Fail-cancelled Voyra mirrors (`BookingService.isFailCancelled`) are excluded.
- **Follow-up flag:** any OPEN follow-up past its due date adds **Follow-up required**.
- **Scheduled cancellation that has happened:** if its booking is already cancelled or has an active Voyra request, it
  reads as done. This is worked out on read, with no hook.

**API:** classified CRM_CORE in `ControllerSkuTable`. Owner and agent write; the accountant reads.
- **List:** `GET /api/cancellations?view=open|due|all&agentId&q&page&size`.
  - Rows are bookings in the caller's `accessibleToAgent` scope that are CANCELLED (not fail-cancelled), have a Voyra
    request, or have a follow-up. This is one JPA Specification with an EXISTS on public `platform_cancel_request` by
    `platform_booking_id` and `tenant_id` (`public` is on the tenant search_path), so paging is done in SQL.
  - `open`: an active request, a refund still open, or an open follow-up.
  - `due`: an open follow-up due today or earlier, or an OPEN request. It feeds the dashboard cards (`size=5`).
- **Detail:** `GET /api/cancellations/{bookingId}` returns status, charges, refund progress, the Voyra outcome,
  follow-ups and activity.
- **Follow-up writes:**
  - `POST /api/bookings/{id}/cancellation-follow-ups` with `{kind, dueDate | inDays (1|2), note, assignedAgentId}`;
  - `PATCH .../{followUpId}` to reassign or move the date;
  - `PATCH .../{followUpId}/done`.
- **History:** every write records a `booking_activity` row. Cancel, refund and Voyra steps are read from the existing
  fields and events, never copied.
- **Escalations:** open cancellation follow-ups past due also join `EscalationService` (`:57-66`) as a new type, so the
  existing `NeedsAttentionCard` on both dashboards shows them.
- **Requests from non-bookers:** an agent who is not the Voyra booker sees the status read-only. Withdraw and new
  requests stay on the booking page, under the existing booker rule.

**Frontend:**
- **Sidebar entry "Cancellations":** in the owner's CRM group, the agent menu, and read-only for accounts. Add `skuForPath`
  entries for `cancellations` (CRM_CORE) on all three consoles in `lib/entitlements.ts`.
- **List page:** `components/cancellations/CancellationsList.tsx`, mounted by wrapper pages under
  `app/{agency,agents,accounts}/cancellations`.
  - Open / All tabs, status badges, due date, assignee and refund progress.
  - A row opens a drawer with charges, refund steps, the Voyra outcome, follow-ups (add, done, reassign) and the
    activity list.
- **Booking Manage page:** a "Schedule cancellation" action (after 1 day, after 2 days, or a date, plus a note) on a live
  booking, beside Cancel / Request cancellation.
- **Dashboard card "Cancellation reminders":** on `/agents/dashboard` and `/agency/dashboard`. Owners land on
  `/agency/home`, so for CRM Core agencies the home page gets a slim "Due today" strip: cancellations due and boarding
  passes pending, each linking to its page.
- **Popup:** once a day when the home or dashboard opens with something due today ("3 cancellations are due today"),
  kept in `localStorage` per user per day inside try/catch.

## 4. Boarding passes for tomorrow's flights, and ancillaries

**Data (tenant V5):**
- `boarding_pass`. Columns: `id`, `booking_id`, `travel_date`, `status` (GENERATED | NOT_NEEDED), `reason` (≤300,
  optional), `file_key` / `file_name` (optional proof), `updated_by` / `name`, `updated_at`. `UNIQUE (booking_id,
  travel_date)`.
- No row means pending, and a row whose date no longer matches a flight date of its booking is ignored, which also
  covers re-dated tickets.
- Every action writes `booking_activity`.

**Which flights count for a date D (India date):** `LocalDate.ofInstant(clock.instant(), Asia/Kolkata)` plus 1 day, on
the `FinancialYear` / `SubscriptionBillingService.INDIA` pattern. The UI sends no date by default.
- A FLIGHT booking in the caller's scope whose `journey_date` or `return_date` is D, **or** that has an E_TICKET voucher
  with a leg or detail date on D (`booking_document.legs[].travelDate`, `details.journeyDate/returnDate`, read as in
  `VoucherTrip` and `BookingCompletionService.endDate`).
- Status must be CONFIRMED, or CONFIRMED or COMPLETED when D is in the past (the nightly completion job).
- Bookings with an active Voyra cancel request are left out, and passengers cancelled by a completed partial request
  are left off the row (`CancelParts`).

**API:** CRM_CORE; owner and agent.
- `GET /api/boarding-passes?date&status=pending|all&page&size` returns one row per booking and date: sector (from the
  legs when known), PNR, passengers, client, agent and status.
- `POST /api/boarding-passes` (multipart) takes `{bookingId, travelDate, status, reason?}` plus an optional `file`
  (UploadValidator, 10 MB, its own storage category `boarding-passes`, allowed on Voyra mirrors).
- `DELETE /api/boarding-passes/{id}` resets the row to pending (undo, recorded).
- `GET /api/boarding-passes/{id}/file` downloads the proof. This is the "share".

**Frontend:**
- **Dashboard card "Tomorrow's flights: boarding passes"** on both dashboards, plus the home strip: the top 5 pending,
  each with "Mark generated" (optional file) and "Not needed" (optional reason).
- **See all:** goes to `app/{agency,agents}/boarding-passes`, rendered by `components/boarding/BoardingPassList.tsx`, with
  a date picker defaulting to tomorrow, pending / all, proof download and undo.
- **Booking Manage page:** shows each date's status and file.
- **Gating:** `skuForPath` entries.

**Ancillaries:**
- **Type:** add `ANCILLARY` to `BookingDocumentType`, the TS unions (`lib/api/types.ts:2318`, `lib/mockData.ts:152`) and
  `DOC_TYPES` in `BookingDocuments.tsx:37`. It is offered on FLIGHT bookings only and refused on Voyra mirrors, where
  vouchers are refused anyway.
- **What it stores:**
  - Kind: Seat, Meal, Extra baggage or Other.
  - Description.
  - Supplier and reference.
  - Price: purchase price plus markup through `CostMarkupFields`.
  - Passengers ticked from the booking's travellers. `applyTrip` (`BookingDocumentService:249-253`) must keep
    `travellers` for ANCILLARY.
  - `details.ancillaryKind` / `description`, added to the FLIGHT key whitelist in `VoucherDetails.clean`.
- **Money:** it adds to the booking's cost and price, drafts its own supplier bill, and needs no fare breakdown.
- **Invoice:** it goes on the flight's draft invoice, or on a second invoice once the ticket is invoiced (as
  `InvoiceLineSource.linesFor` already does).
- **Air invoice PDF:** `AirInvoicePdfRenderer` prints it as its own row ("Seat 12A – <passenger>", one amount), not as a
  flight with Basic = total.
- **Cancellation:** cancelling the booking treats the ancillary like the rest of the price; the charges entered decide
  what is kept.

## 5. Visa PAN and per-country document checklists

- **PAN:** tenant V6 adds `member.pan_number` varchar(10), upper-cased. It reuses the PAN regex and message of
  `AgencyApplicationCreateRequest:31` and is added to every hand-built mapper (`MemberMapper`, `MemberService`,
  `LeadService`, `VisaService`). It is editable in the member form and shown in the lead's traveller manifest and the
  visa checklist table; a scan uses the existing "PAN Card" document type.
- **Checklist per country:**
  - **Storage:** a new `AgencySettingKind` `VISA_DOCUMENT`, with a nullable `country` column on `agency_setting` (V6).
    This reuses the Settings CRUD and soft delete, and nothing is seeded.
  - **Settings:** a "Visa checklists" section (owner) to pick a country and add its document names.
  - **Classification:** classify the endpoint as the settings controller already is.
- **Lead Visa service:**
  - `VisaChecklistEntry` gains `documents: Map<name, Boolean>`.
  - The toggle request takes a separate `document` field, checked against the service country's current list. Unticking
    a name that has since been removed from Settings stays allowed. The fixed 6 steps and the unknown-key rejection for
    them stay.
  - `VisaChecklistTable` shows the country's documents as extra ticks per traveller, and a removed name stays visible
    while ticked. Ticks are keyed by name, so a rename shows as a new unticked item (accepted).
- **Standalone visa case:** gets `visa.documents` jsonb (V6) with the same ticks for its country in
  `VisaChecklistModal`.

## Not in this round

- Emailing or WhatsApping boarding passes.
- Buying ancillaries through Voyra or Tripjack, and ancillaries on Voyra-booked flights.
- Enforcing credit limits.
- The 8 hand-set cancellation statuses.
- A notification bell in the agency shell.

## Tests and verification

- **Backend:** unit and controller-slice tests per endpoint, covering:
  - roles, agent scope and the CRM_CORE gate;
  - `ControllerSkuTable` classification;
  - validation messages;
  - the `CancellationStatus` table;
  - boarding-pass date selection: booking dates, voucher-only return, COMPLETED for past dates, active Voyra request
    excluded, partial cancel, IST midnight edge via `Clock`;
  - ancillary voucher, invoice line and PDF row;
  - each migration on a fresh database.
  Then `./mvnw clean verify` (JaCoCo 100 %, blueprint audit). Restart the local jar after each migration.
- **Frontend:** component tests for every new screen and card, 100 % coverage, `tsc`, lint and `npm run build`.
- **E2e:**
  - new `e2e/cancellations.spec.ts` and `e2e/boarding-passes.spec.ts`;
  - an ancillary voucher in the `voucher-*.spec.ts` specs;
  - Open Case without a date in a visa test.
  Run against the Dummy QA Agency only, with no Tripjack call.
- **Docs:** sections in root `CLAUDE.md`, `travel-crm/CLAUDE.md` and `PROGRESS.md`; design doc
  `travel-crm-backend/docs/superpowers/specs/2026-11-22-client-round-design.md` (this plan).
- **Before any push:** run `regression-tester` in pre-merge mode. Commit and push only when asked.
