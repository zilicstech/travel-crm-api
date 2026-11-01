# Unified bookings: one booking list for agencies on a CRM plan

Date: 2026-10-31. Status: draft for owner approval (builder check and bmad-review folded in). Repos: `travel-crm-backend`, `travel-crm`, branch `nitin-dev`.

## Why

An agency on Voyra CRM Lite or Premium sees its bookings in two places. A flight or hotel booked with Voyra lives in
"My Bookings" (Platform Bookings), and, if a client was picked, a read-only copy appears in the CRM Bookings list
with a "Manage in Platform Bookings" link. The agency has to remember which list holds what, and the CRM copy is
missing until the supplier has ticketed, so an in-flight or failed Voyra booking never shows in the CRM at all.

The owner's ruling: a Voyra booking is just another booking. It sits in the one Bookings list beside bookings made
with any other supplier, its vendor is "Voyra Platform", and the list filters on that. What the CRM already does
for a booking (client, payments and receipts, invoice, documents, remarks, reports) applies to it; what Voyra
owns (the trip itself, the supplier price, ticketing, cancellation) is shown there and stays locked.

Voyra Basic agencies have no CRM Bookings list, so for them nothing on screen changes.

## Goals

1. Every booking has a client. Book with Voyra, Quick book and the manual booking form all require one before the
   booking is made, and the client dropdown can create a client on the spot.
2. For an agency with CRM Core, every Voyra booking is one row in the Bookings list from the moment the booking is
   sent to the supplier, with vendor "Voyra Platform", and the list can be filtered to Voyra bookings.
3. The CRM booking detail of a Voyra booking shows the live Voyra status, PNR and ticket numbers, the itinerary
   or stay, the history, **Cancel with Voyra** and **Download ticket**, so nothing needs the Platform Bookings page.
4. Trip facts, travellers, supplier cost and supplier payments of a Voyra booking cannot be edited in the CRM.
   Money on the client side (selling price through the markup, payments, invoice, extra documents, remarks) can.
5. The selling price of a Voyra booking is the published fare by default. A ticket downloaded with a markup makes
   the selling price published fare plus that markup; a later download with another markup overrides it.
6. For CRM agencies the Platform Bookings page leaves the navigation of owner, agent and accountant. Old links
   still land somewhere sensible.

## Non-goals

- No change to wallet keys, the platform state machine, pricing, Voyra's earnings or the ledger postings.
- Basic agencies keep the Platform Bookings page exactly as it is.
- No backfill of Voyra bookings made before this change without a client. Live bookings are still switched off,
  so these are QA rows; they stay reachable on the Platform Bookings detail by URL and in the admin console.
- No new booking types, and no change to how non-Voyra bookings behave.
- The platform admin console (`/platform/bookings`) is untouched.

## Anti-goals (a failure even if it works)

- Two rows for one Voyra booking, or a Voyra booking that never gets its CRM row while the agency is on a CRM plan.
- A CRM edit that silently disagrees with what the supplier holds (dates, travellers, PNR, supplier cost).
- Money posted twice: the supplier payment from the wallet, or the cancellation refund, recorded both by the
  platform and by hand.
- A ticket price on the customer's e-ticket that differs from the booking's selling price for no reason the agent
  can see.
- Voyra's own figures (net, base, commission, supplier ids) leaking into the CRM row or its API shape.

## How it works today (what the design builds on)

- `platform_booking` (public schema) is the supplier and money record. `PlatformBookingService` is its state
  machine; `PlatformBookingMirrorService.sync` runs after a transaction commits and, only when the row has a
  `client_id` and is TICKETED or CANCELLED, asks `PlatformBookingMirrorTenantService` (REQUIRES_NEW, run as the
  `SystemActor`) to create the CRM booking (`Booking.platform_booking_id`, vendor = the system vendor "Voyra
  Platform", net cost = displayed fare, an E_TICKET or HOTEL_VOUCHER document, supplier invoice approved and paid
  with `PaymentMode.VOYRA_WALLET`) or to cancel it (supplier charge = displayed minus refund, refund recorded against
  the Voyra wallet account). `mirror_status` is NONE, PENDING, DONE or FAILED; `PlatformBookingMirrorRepairJob`
  retries PENDING and FAILED every 10 minutes. `tenant_booking_id` on the platform row and `tenantBookingId` on
  `PlatformBookingResponse` already link the two.
- The CRM side treats a row with `platform_booking_id` as "managed from the platform": `PlatformManaged.check`
  refuses update, status change, deadlines, delete, document edits, cancel and supplier refund actions with 409
  `MANAGED_FROM_PLATFORM` unless the `SystemActor` is active. Client payments, accountant remark, client refund and
  the lifecycle payment record are allowed.
- `BookingCreateRequest.clientId` is already required ("Client is required"), so Quick book flight, Quick book hotel
  and the manual booking form already have a required `ClientPicker` with a "Create New Client" row that opens
  `AddClientModal`. Log Booking from a lead service uses the lead's client. Only `BookWithVoyraModal` has an optional
  "Client (optional)" picker, and `FlightReviewRequest` / `HotelReviewRequest` accept a missing `clientId`.
- The Bookings list (`BookingsList.tsx`, `GET /api/bookings`) already has a Vendor filter (`vendorId`), a Vendor
  column (`supplier`) and a "Voyra" badge on rows with `platformBookingId`. The CRM booking detail
  (`BookingManage.tsx`) hides Edit, Cancel and Delete for a Voyra row, strips the supplier-side lifecycle actions and
  shows a "Manage in Platform Bookings" link.
- The Platform Bookings detail (`PlatformBookingDetail.tsx`) and its pieces (`CancelPlatformBookingModal`,
  `TicketDownloadDialog`, `TicketDownloadsCard`, `BookingStatusPanel`, `BookingTrackerModal`) take a booking id and
  hrefs only; none reads the router.

## Part 1: a client on every booking

**Server.** `FlightReviewRequest.clientId` and `HotelReviewRequest.clientId` become required (`@NotBlank`, message
"Client is required", 400). The existing `requireLinks` check (client exists in this agency, lead and service belong
to it) stays as the second line. Confirm carries no client; the one stamped at review is used, as today. Nothing
changes for Quick book or the manual form: the CRM request already requires a client.

**Book with Voyra dialog.** The client picker is shown on every plan (clients are a Basic feature and the clients
store loads on Basic too; today the picker is rendered only with CRM Core, so Basic agencies see it for the first
time), labelled "Client", required, with the same "Create New Client" row and `AddClientModal` as Quick book. A client created there is selected at once and seeds the contact email, phone and the first
passenger from its main contact, exactly as picking an existing client does. Next (to the price review) is
disabled until a client is chosen; when the dialog is opened from a lead the picker is prefilled and locked, as today.

**Consequence for Basic agencies.** Every Voyra booking now has a client, so every one gets a CRM record (there is
no CRM Core check in the mirror today and none is added). A Basic agency never sees it, and it becomes its history
if it upgrades. The supplier payment postings in its tenant ledger already happen today whenever a client was picked.

## Part 2: the CRM record exists from the moment the booking is sent

Today the CRM record appears only once the supplier has ticketed. From now on `sync` is a reconciler: whenever the
platform row changes in a way the CRM should show, the platform marks `mirror_status = PENDING` and schedules
`sync` after commit, and `sync` makes the CRM row match the platform row's current status by running the phases
below in order, each one skipping when the CRM row already shows its result. A repeat run, the repair job and a
status that moved on between two runs all end in the same place.

**Platform transitions that mark PENDING.** Every move of the platform status from BOOKING onwards sets
`mirror_status = PENDING` in the same transaction, through one helper on the state machine (not a call added to
each site by hand): the move to BOOKING in `markBooking`; to TICKETED (`ticket`); to FAILED from the supplier
answer, the poller or an admin resolve; to CANCELLED from `cancelLocked`, `settleConfirmedCancel` and
`resolveCancel`, whatever the mirror status was before (today `cancelLocked` flips only a DONE mirror). UNKNOWN and
CANCELLING change nothing in the CRM and mark nothing. HELD, REVIEWING and ABORTED never have a CRM row: an abort
only happens before the book call is sent. A unit test per transition pins the flag.

**When `sync` runs.** As today, never inside a transaction. After the TICKETED and CANCELLED transitions it runs
where it runs today. For BOOKING it runs in the confirm thread **after the book call has returned or failed**
(before `awaitOutcome`), so the supplier is called straight after the hold and the CRM row exists before confirm
answers; while the book call is in flight the row does not exist yet, which the tracker and links in Part 5
handle. The poller and the repair job (PENDING and FAILED rows, every 10 minutes) are the backup for a confirm
thread that died in between. The repair job's attempt cap counts `MIRROR_FAILED` events since the platform row's
last status change, not over its whole life, so a day of ensure failures at BOOKING cannot stop the ticket phase.

**`sync` guards.** Unchanged for a missing row or a row without a client (old rows). It acts on BOOKING, UNKNOWN,
TICKETED, FAILED and CANCELLED and returns for everything else; DONE short-circuits only because every transition
above resets it.

**Concurrency contract.** The confirm thread, the poller and the repair job (possibly on another instance) can all
call `sync` for one row. Every tenant phase takes a lock before it reads the CRM row: the ensure phase a
transaction-scoped advisory lock keyed on the platform booking id (`pg_advisory_xact_lock`) around
`findByPlatformBookingId`, and every later phase `findByIdForUpdate` on the CRM row, re-reading its state under the
lock before acting. The UNIQUE on `booking.platform_booking_id` (tenant V78) is the last line: a unique violation
in ensure means "already created" (re-read and continue), never a FAILED mirror. One test runs two syncs
concurrently and asserts one CRM row and one supplier payment.

**Tenant phases** (`PlatformBookingMirrorTenantService`, each REQUIRES_NEW, run as the `SystemActor`, in this order):

1. **Ensure** (every status it acts on): if no CRM booking has this `platform_booking_id`, create one. This is a
   dedicated creation path in `BookingService` for the mirror, not `logBooking` with flags: it creates the booking
   PENDING with the trip facts today's `create` / `createHotel` map, the client, agent (`agentFor`), lead and
   service links, vendor "Voyra Platform", net cost = displayed fare in base currency, selling price per Part 3,
   notes "Booked with Voyra", `overrideCreditLimit`, and the voucher document (E_TICKET or HOTEL_VOUCHER) carrying
   the purchase price, currency and rate, with **no** reference (PNR), **no** supplier bill, **no** auto-confirm
   (`readyToConfirm` never fires because there is no reference), **no** `onBookingLogged` on the lead service and
   **no** "booking logged" timeline line. `tenant_booking_id` is set on the platform row as today. A missing FX
   rate for a non-INR agency fails the mirror, as today, and the repair job retries.
2. **Ticket** (platform row has `ticketed_at`, whatever its current status): skip if the CRM row is already
   CONFIRMED (or COMPLETED) with this PNR. Otherwise set PNR, ticket numbers, the voucher's reference, legs,
   travellers, details, charges and issued date; create, approve and pay the supplier bill from the Voyra wallet
   account (today's `payFromVoyraWallet`, at the voucher's stored rate); `onBookingLogged` on the lead service plus
   the timeline line creation writes today; status CONFIRMED. A row cancelled by the fail phase is **revived**
   here: recognised by `platform_booking_id` set, `cancelled_by` = the system actor and no cancellation money
   (never by the reason text), it gets its cancel reason, cancelled-at, cancelled-by cleared, refund state back to
   NOT_APPLICABLE's default, status CONFIRMED and one timeline and audit line "Voyra ticketed this booking after
   all". This happens when an admin resolves a FAILED row and the poller (which re-checks admin-resolved FAILED
   rows for 72 hours) finds it ticketed. The tenant ledger then shows the supplier bill paid in full from the Voyra
   wallet account even when the platform debited part of it as a DUE receivable (hold released earlier); the
   agency's wallet statement is the truth for what it owes Voyra, as it is today for every late ticket.
3. **Fail** (FAILED): skip if already cancelled. Set status CANCELLED, cancelled-at now, cancelled-by the system
   actor, cancel reason "Voyra could not complete this booking; nothing was charged", refund state NOT_APPLICABLE,
   and **no** cancellation money fields; one timeline line on the lead when there is one. This does not go through
   `BookingCancellationService` (no supplier charge, no client refund, the hold was released by the platform) and
   the lead service is untouched (it was never marked BOOKED).
4. **Cancel** (CANCELLED): today's `cancel`, unchanged: supplier charge = displayed minus refund, refund recorded
   against the Voyra wallet account, through `BookingCancellationService`, at the voucher's stored rate. Because
   the phases run in order, a platform row cancelled while its CRM row was still PENDING gets the ticket phase
   first (the platform only cancels a ticketed row, so the debit and PNR exist) and then the cancel, so the
   supplier payment and the refund are both posted. A CANCELLED row with no usable refund figure (`cancel_refund`
   null) fails the cancel phase with an alert, as today, until the admin's `resolveCancel` supplies the figure; the
   CRM row stays CONFIRMED meanwhile and its Voyra card shows "Cancelled, refund being settled by Voyra".

**Finish** stays as today (DONE on success, PENDING if the status moved meanwhile, FAILED plus one platform alert
on error). `PlatformBookingPollJob`, the DPDP purge rule (hold passengers back while the mirror is PENDING or
FAILED) and `BookingCompletionService` (skip a Voyra row whose platform booking is not settled) are unchanged. A
client deleted between review and the ensure phase fails the mirror with the existing "Client not found" alert.

**No client money before ticketing.** While a Voyra row is PENDING (Voyra is still booking) the CRM refuses every
client-side money action on it with 409 `WAITING_FOR_VOYRA` ("Voyra is still booking this; bill and collect once
it is confirmed"). The rule lives in one place (`BookingService.requireBillable(booking)`: `platformBookingId`
set and status PENDING) and every entry point calls it: invoice create, adding the booking to an existing draft or
combined invoice, proposal-to-invoice, receipt allocation to the booking, the lifecycle payment record, apply
wallet credit, and the manual payment-status override. The lifecycle leaves GENERATE_INVOICE and RECORD_PAYMENT
out of `allowedActions` for such a row. A controller-slice test covers each path. This keeps the fail phase free
of money (nothing to refund or credit) and makes a revival a status change only. A booking is PENDING for seconds
to minutes normally, hours when UNKNOWN.

**What the CRM row carries per platform status**

| Platform | CRM booking status | Notes |
|---|---|---|
| BOOKING, UNKNOWN | PENDING | next step text "Waiting for Voyra"; no client money |
| TICKETED | CONFIRMED | PNR, tickets, supplier paid, lead service BOOKED |
| CANCELLING | CONFIRMED | unchanged until the refund is known; the Voyra card shows "Cancelling" |
| FAILED | CANCELLED (fixed reason, no money) | revivable |
| CANCELLED | CANCELLED through the CRM cancel | supplier charge and refund posted |

## Part 3: selling price and markup

**Default.** The CRM row's markup is FLAT with value = published fare minus displayed fare (both converted to the
agency's base currency at the voucher's stored rate; 0 when the platform row has no published fare), so the
selling price equals the published fare and the profit equals the agency's discount. `MarkupDefault` is no longer
read for Voyra bookings (it stays for whatever else uses it).

**Ticket markup moves the selling price.** The markup of the newest e-ticket download (`platform_booking_ticket`,
highest `seq`) is the booking's markup: selling price = published fare + that markup. Concretely:

- `setMarkup(pb, markup)` is a new tenant method (REQUIRES_NEW, `SystemActor`): it sets the voucher's FLAT markup
  to published + markup minus displayed, converted at the voucher's **stored** purchase rate (the ensure phase is
  the only FX lookup; nothing looks a rate up again), recomputes the voucher's selling price with `MarkupMath`,
  re-sums the booking's selling price and profit from its live vouchers, audits the change and re-syncs the
  payment status if invoiced. It is a **markup-only reprice**: it never touches the net cost, the vendor or the
  supplier bill, and does not go through `reverseVoucher` / `applyVoucher` (which refuse an approved bill and would
  draft a second one). Allowed on a CONFIRMED or COMPLETED CRM row; refused on a CANCELLED one (409
  `MARKUP_NOT_APPLICABLE`).
- `PlatformBookingTicketService.create` is restructured like `confirm`: a short transaction validates the markup
  and reads the row; `setMarkup` runs with no platform transaction open (the blueprint rule: tenant-switching work
  never nests inside the caller's transaction, and the pool is small); then a transaction locks the platform row
  and inserts the ticket (`seq` under the lock). Failure matrix: `setMarkup` refused or failed, no ticket and no
  price change, the error goes to the agent; ticket insert failed after `setMarkup` committed, the price has moved
  and no ticket exists, the agent sees the error and downloads again.
- **Invoiced guard.** `setMarkup` does not use `assertNotInvoiced` (which refuses any change once a draft or live
  invoice covers the voucher). Its one rule: the new selling price, rounded as the CRM stores it, may not be below
  the booking's invoiced total; otherwise 409 `PRICE_BELOW_INVOICED` ("This price is below what you have invoiced;
  adjust the invoice first") and, for a download, no ticket is made, so the e-ticket price and the CRM price
  never disagree. A higher markup after invoicing is allowed and leaves an uninvoiced balance, so GENERATE_INVOICE
  reappears through the lifecycle's existing uninvoiced rule. A download with the same markup as the newest one
  changes nothing and is never refused.
- **Who gets the CRM write.** `setMarkup` runs only when the tenant has CRM Core and is not on a READ_ONLY plan
  (`EntitlementService`); otherwise the ticket still downloads at its price and the CRM row (hidden on Basic,
  frozen on READ_ONLY) is left alone. A SUSPENDED agency keeps its one allowed write, the ticket download, and
  `setMarkup` runs for it as the system actor, the way the mirror writes to a suspended tenant today. This is
  pinned by tests per case.
- An old-style row (mirrored before this change at cost plus the markup default) is repriced the first time its
  markup is set; that is the only way an old row's price changes.
- The ensure and ticket phases read the newest ticket's markup (0 when none) when they set the markup, so a ticket
  downloaded before the CRM row existed is not lost.
- A download with markup 0 sets the selling price back to the published fare.
- `TicketDownloadDialog` opens with the newest download's markup prefilled instead of 0, so "download again" keeps
  the price unless the agent changes it.

**Rounding and currency.** CRM selling prices are whole units of the base currency (`MarkupMath` rounds to 0
decimals); a ticket markup may carry paise. The e-ticket prints the exact rupee ticket price; the CRM row shows it
in base currency at the stored rate, rounded. Accepted.

**Set markup without a ticket (my call).** Hotels have no e-ticket, so a hotel booked with Voyra would be stuck at
the published rate. A small **Set markup** action on the Voyra card of the CRM booking detail (both products,
owner or the booking agent, not accountant) takes a rupee markup with the same rules as the ticket markup (0 to the
published fare, two decimals) and calls `PATCH /api/platform-bookings/{id}/markup` `{markup}`, which runs the same
`setMarkup`. For a flight, the next ticket download opens with that markup prefilled. The smaller option is to drop
this and let hotels stay at the published rate until a later round.

**Invoices.** A client invoice already raised is not changed by a later markup. The detail shows the booking's
selling price beside the invoice total, and the agent handles a difference through the normal invoice path.

## Part 4: the CRM booking detail of a Voyra booking

`BookingManage.tsx` keeps its header, facts, metrics, vouchers, money records and lifecycle tracker. For a row with
`platformBookingId`:

- The "Manage in Platform Bookings" link and its copy go. In their place a **Voyra card** (the body of today's
  `PlatformBookingDetail` without its page header and back link, extracted into a component used by both the
  Platform Bookings detail page and this card) shows: live status chip (polling every 3 seconds while HELD,
  BOOKING, UNKNOWN or CANCELLING, as `BookingStatusPanel` does), PNR and ticket numbers, the itinerary or stay,
  the supplier message in the agency-safe wording, the history timeline, the ticket downloads card, and the
  actions **Cancel with Voyra** (TICKETED; owner or the agent who booked; opens `CancelPlatformBookingModal`),
  **Download ticket** (flight, TICKETED, published fare known; opens `TicketDownloadDialog`) and **Set markup**.
  An accountant sees the card read-only. An action refused by the server (the agent no longer handles the
  product) shows the server's message in the card.
- After Cancel with Voyra completes, the page reloads the CRM booking and lifecycle and shows "Voyra is updating
  this booking" until the CRM status reads CANCELLED (the cancel sync runs after commit, usually within a second;
  a Tripjack cancel that is still CANCELLING shows that status instead).
- The card is rendered only when the session may read the platform row: owner and accountant always, an agent
  when `BookingResponse.platformBookedBy` (new, set on the detail only: the user id that booked, from the platform
  row) equals the session user. Otherwise the card's place reads "Booked with Voyra by another user of your
  agency. Ask them or the owner for its status." and nothing is fetched. The platform row is visible only to its
  booker, the CRM row to anyone who may handle the service type.
- Everything else stays as today: Edit details, Cancel booking and Delete stay hidden for a Voyra row, the
  supplier-side lifecycle actions stay stripped, client payments, invoice, extra documents and remarks work once
  the row is CONFIRMED.

## Part 5: lists, navigation and old links

**Bookings list.** A quick filter "Booked with Voyra" (a toggle chip beside the Sale type and Status selects) sets
the existing `vendorId` filter to the system vendor (the vendors store knows `isSystem`). The Vendor column already
reads "Voyra Platform" and the "Voyra" badge stays. A PENDING Voyra row's next step reads "Waiting for Voyra".
The list refreshes while it holds a PENDING Voyra row (poll every 5 seconds, as the platform table does), so a
booking just sent moves to Confirmed without a reload.

**After confirm.** For an agency with CRM Core, `BookWithVoyraModal` sends the user to `<base>/bookings?track=<platform
id>` instead of `<base>/platform-bookings?track=`. The Bookings list mounts the existing `BookingTrackerModal` for
`?track=`; its "Open booking" goes to the CRM booking (`tenantBookingId` from the platform response) once the row
exists and to the Platform Bookings detail (`<base>/platform-bookings/<id>`, still served, only unlisted) while it
does not, and the list reloads on each tracker change. Only the booker ever lands here, so the tracker never meets
the agent-visibility 404. Basic agencies keep today's route.

**Navigation.** When the agency has CRM Core: the owner's "My Bookings" (Book group), the agent's "Platform
Bookings" and the accountant's "Platform Bookings" entries are hidden. The three `platform-bookings` list pages
replace themselves with `<base>/bookings` once entitlements are ready and CRM Core is present; the three
`platform-bookings/[id]` pages fetch the platform booking and replace themselves with
`<base>/bookings/<tenantBookingId>` when it has one, else render the detail as today (an old row without a client,
a row whose mirror has not run yet or failed). Basic agencies: no change. The agent dashboard redirect
(`EntitlementGate`) is unchanged.

**Home and booking report.** "My bookings", "Cancellations", "View all" and the next-trips rows on the agency home,
and "All bookings" on the booking report, point at `/agency/bookings` (rows at `/agency/bookings/<tenantBookingId>`,
or the Platform Bookings detail when `tenantBookingId` is null) when CRM Core is present, else at Platform Bookings
as today. The summary's booking rows are `PlatformBookingResponse` and already carry `tenantBookingId`; the
summary keeps reading the platform tables.

## Data

No new migration. `platform_booking.tenant_booking_id`, `booking.platform_booking_id`, `platform_booking_ticket`
and the system vendor flag (tenant V79) already exist. Rows mirrored before this change keep their selling price
(cost plus the old markup default) until their markup is first set; no migration rewrites them.

**Personal data on Basic.** Every Voyra booking now has a client, so a Basic agency's tenant schema receives a CRM
booking with traveller names and pax types it has no screen for while on Basic (today this already happens
whenever a Basic agency picks a client). The DPDP purge nulls the platform row's passengers and leaves tenant data
alone, as it does for CRM agencies: tenant rows are the agency's own records, kept until the agency deletes them.
This is recorded as the ruling for counsel beside the KYC retention note; the alternative (skip the mirror
without CRM Core and mirror ticketed rows on upgrade) is listed as an owner question.

## API

- `POST /api/platform-bookings/flights/review`, `POST .../hotels/review`: `clientId` required (400 "Client is
  required").
- `PATCH /api/platform-bookings/{id}/markup` `{markup}`: owner or an agent who may act on the booking; TICKETED
  only; same validation messages as the ticket markup; 404 when the platform row has no CRM booking or the tenant
  has no CRM Core; 402 `READ_ONLY` on a read-only plan (checked in the service, since the prefix is NEVER_GATED
  and the entitlement interceptor leaves it alone); 423 while SUSPENDED (tickets stay the one allowed write);
  returns the platform booking.
- Every client-money entry point listed under "No client money before ticketing": 409 `WAITING_FOR_VOYRA` on a
  Voyra booking whose status is PENDING.
- `BookingResponse` gains `platformBookedBy` (user id, detail only). Nothing on the CRM side exposes Voyra's
  figures.

## Constraints

- Blueprint layering and the `REQUIRES_NEW` tenant-switch rule for every cross-schema write, `TenantContext`
  restored in `finally`. `sync` and `setMarkup` never run inside a platform transaction.
- Any new controller or endpoint is classified in `ControllerSkuTable`; the contract test and `blueprint-audit.sh`
  fail otherwise.
- 100 % line, branch and method coverage on both repos (`mvn verify`, Vitest thresholds); Playwright e2e for the
  journeys below; the regression-tester agent in `pre-merge` mode before any PR.
- Logs carry no passenger names, passport numbers, emails or phones.
- Local runs use the mock booking providers (`.claude/run/start-backend-mock.sh`); nothing calls Tripjack.
- UI follows `travel-crm/UI_BLUEPRINT.md` and the existing `components/ui` primitives.

## Left to the builder

- The shape of the mirror's dedicated creation path in `BookingService` and of the markup-only reprice.
- The exact split of `PlatformBookingDetail` into the shared card and the page wrapper, and where the polling lives.
- Mapping the mock provider's ticket details into the ticket phase (same code as today's create).
- A lead service dropped, or a lead lost, while Voyra was booking: the ticket phase still marks and writes the
  timeline line; what else to signal is the builder's call.
- Which e2e specs move from the Platform Bookings page to the Bookings list. The QA tenants used by
  `platform-flight-booking.spec.ts`, `platform-hotel-booking.spec.ts`, `platform-ticket.spec.ts`,
  `booking-tracker.spec.ts` and `platform-flight-roundtrip.spec.ts` are on a Suite plan, so those journeys now run
  through `/agency/bookings`; `shell-and-guards.spec.ts`, `agency-home.spec.ts` and `subscriptions.spec.ts` assert
  the "My Bookings" entry or the `/platform-bookings` route and change too; the Basic-plan journey (tab unchanged)
  is covered on the QA Basic agency from `.claude/run/seed-plan-agencies.py`.
- Invariants to add or change in `.claude/skills/full-regression/scripts/invariants.py`: a platform row with a
  client in BOOKING, UNKNOWN, TICKETED, FAILED or CANCELLED has exactly one CRM booking; the CRM status matches the
  table in Part 2 once the mirror is DONE; a FAILED row's CRM booking has no supplier bill, no supplier payment and
  no cancellation money; a PENDING CRM row has no invoice and no receipt; the existing "mirror DONE means CRM
  CANCELLED" check is scoped to platform CANCELLED. Matrix rows UB1 onwards in `scenario-matrix.md`, and the docs
  (root `CLAUDE.md`, `travel-crm/CLAUDE.md`, `PROGRESS.md`).

## Calls made in this design (owner can override)

1. The CRM row is created when the book call is sent (BOOKING), not at the wallet hold, so an abort never needs a
   CRM cancel and a failed booking shows as cancelled with "nothing was charged". The sync runs after the book
   call returns, so nothing sits between the hold and the supplier.
2. A failed Voyra booking becomes a CRM CANCELLED row with a fixed reason and no money, and is revived if the
   supplier tickets it after all.
3. The selling price default is the published fare; the markup default per product (`MarkupDefault`) no longer
   applies to Voyra bookings.
4. **Set markup** exists on both products so hotels are not stuck at the published rate. Smaller option: drop it.
5. A later markup never changes an invoice already raised; a markup that would price the booking below what is
   invoiced is refused (ticket download included); a higher one is allowed and leaves an uninvoiced balance.
6. Basic agencies get a hidden CRM record for every Voyra booking (no CRM Core check added); see "Personal data on
   Basic".
7. The list shows the CRM status only (Pending while Voyra is booking); the live Voyra status is on the detail.
8. Old Platform Bookings links redirect for CRM agencies rather than showing the old page; the detail page stays
   served for rows without a CRM booking.
9. No backfill of client-less Voyra bookings.
10. No client money (invoice, payment, receipt, wallet credit, status override) on a Voyra booking until Voyra has
    ticketed it.
11. The CRM write behind a ticket download is skipped on Basic and READ_ONLY plans and runs on SUSPENDED.
12. An agent sees the Voyra card only on bookings they made themselves (`platformBookedBy`).

## Owner ruling 2026-11-01: no CRM record on Basic

The owner replaced the Basic consequence above (Part 1) and the personal-data ruling: a Basic agency has one bookings
tab and gets no CRM record of a Voyra booking; subscribing brings the CRM features to the same bookings, not a second
list. The mirror's `sync` writes nothing for a row with no CRM booking in an agency without CRM Core and leaves
`mirror_status` NONE. Once the agency has CRM Core, the repair job (every 10 minutes) backfills each of its rows still
NONE with a client at BOOKING, UNKNOWN, TICKETED, FAILED or CANCELLED: queued like a status move and synced, so the
CRM booking shows the booking as it is now (ticketed: confirmed with the supplier bill paid; cancelled: the CRM
cancel; failed: cancelled, nothing charged). Costs: up to 10 minutes after an upgrade before the earlier bookings show;
a booking whose passengers were purged comes in without traveller names; a CRM agency's failed bookings from before
unified bookings (never mirrored) are backfilled as cancelled, nothing charged. A CRM booking that already exists stays
in line after a downgrade.
