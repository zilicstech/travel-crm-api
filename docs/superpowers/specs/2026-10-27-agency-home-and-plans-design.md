# Agency home, three plans and the account hub (design, 2026-10-27)

## Why

Agencies come to Voyra first to book flights and hotels from the wallet. Today an owner logs in to
a CRM dashboard and has to find "Search Flights" in a sidebar of twenty items. B2B portals the
owner's clients already use (MakeMyTrip myBiz, the reference screenshots) open on a search box.
The owner wants the same in Voyra's own look, and wants the product sold as three plans so a free
agency sees a clean booking portal and a paying agency sees the CRM and finance tools it paid for.

Success: an owner logs in and can search a flight within one click; a free agency sees nothing it
cannot use except clearly locked upgrades; the platform admin can put any test agency on any of
the three plans and see the difference immediately.

## The three plans (owner's split, sketch of 2026-10-26)

| Plan (shown name) | Internal plan | Price | What it unlocks |
|---|---|---|---|
| Voyra Basic | `STARTER` (renamed on screen only) | Free | Wallet and top-ups, onboarding, My bookings (platform bookings), flight and hotel search and booking, cancellations and refunds of those bookings, client management, profile and settings, basic reports |
| Voyra CRM Lite | `LITE` (new) | Platform setting `sku_prices.LITE` (placeholder 2,999/month incl. 3 staff seats) | Basic plus leads, service desk, agent management, lead reports, agent reports, supplier (vendor) management, CRM booking management, calendar and handover, visa desk |
| Voyra Premium | `SUITE` (renamed on screen only) | `sku_prices.SUITE` (4,999 incl. 5 seats, unchanged) | Lite plus accountant management, client invoicing, billing, accounts (ledger, payables, banking, journals, statements), accounts reports, accounting refunds |

`CUSTOM` stays as an admin-only plan (a la carte SKUs); agencies never see it offered.

Mapping onto the existing SKU gate (no new SKUs, so `ControllerSkuTable`, the 402 contract and the
invariants keep working):

- Basic: no SKUs. Newly free in Basic: `ClientController` and `ClientSubResourceController` (client
  management), and a new `GET /api/platform-bookings/summary` (basic reports). Both become
  `NEVER_GATED`.
- Lite: `CRM_CORE`, `STAFF_SEATS`, `REPORTS`, `VISA`; seats = 3 included + extra.
- Premium: every SKU including `ACCOUNTS_GST`; seats = 5 included + extra (unchanged).
- Accountant logins need `ACCOUNTS_GST` (Premium). Agent logins need `STAFF_SEATS` (Lite). Today
  both only check the seat count; the accountant check is new. Existing accountants keep working
  (no login is ever cut off, same rule as seats).
- Client invoices stay where they are technically (`CRM_CORE` controllers), but the Invoices menu
  item moves to the Finance group so Lite shows no invoicing in the menu. Ruling to revisit if the
  owner wants Lite agencies to raise client invoices: then invoices move to an `ACCOUNTS_GST` gate.

Existing agencies: the founding agency and every complimentary Suite stay on Suite (= Premium).
New agencies keep the 14-day Premium trial. Nothing in the database changes for them.

## Agency home (owner's landing page)

Route `/agency/home`; `/agency` and the owner's login redirect go there instead of the dashboard.
The CRM dashboard stays at `/agency/dashboard` for Lite and Premium.

Layout, top to bottom, in the Voyra theme (teal `#4a8285`, ink `#172b2c`, Cinzel headings,
Jakarta body, `components/ui` primitives):

1. Hero band: soft teal gradient across the full content width, greeting with the agency logo
   ("Good evening, Ravi"), and on the right a wallet pill (available balance, Top up).
2. Product switcher card floating on the band: Flights, Hotels (icons with labels, the active one
   underlined in teal), like the MakeMyTrip strip.
3. Search card: large-type fields in the MakeMyTrip pattern. Flights: From (city in large type,
   code and airport under it), swap button, To, Departure (day and month large, weekday under),
   Travellers and class. Hotels: City, Check-in, Check-out, Rooms and guests. One-way only for
   flights, because the supplier search is one-way; no round-trip or multi-city toggle is shown
   until it works.
4. Search button: a wide pill overlapping the card's bottom edge. It opens the existing results
   screen (`/agency/flights` or `/agency/hotels`) with the query in the URL and runs the search
   at once, so results, filters, Book with Voyra and markup stay exactly as they are.
5. Recent searches: the last 5 searches as chips (browser storage, per viewer).
6. Quick links strip (the MakeMyTrip "Gift cards, Visa, ..." row): My bookings, Clients, Wallet
   statement, Cancellations, Reports; Lite adds Leads and Visa.
7. Three cards: Upcoming trips (next ticketed bookings by travel date), Needs attention (bookings
   cancelling or awaiting supplier answer, low wallet), This month (bookings, sales, what you
   earned, from the summary endpoint).
8. Plan card: on Basic, "Run your whole agency on Voyra CRM Lite" with the Lite list and an
   Upgrade button; on Lite, the Premium pitch; nothing on Premium.
9. First-login checklist (until done): add your logo, add GST and address, top up the wallet,
   add your first client.

Mobile: the band and card stack; fields become full-width rows; the search button stays full
width under the card. No horizontal scroll at 390 px.

## Side navigation by plan

Groups follow the owner's sketch. Items the plan lacks stay visible with a lock icon (grey,
clicking opens the plan comparison) so an agency sees what it can grow into; a group whose items
are all locked collapses to a single "Unlock CRM Lite" / "Unlock Premium" row.

- Book (Basic): Home, Flights, Hotels, My bookings, Clients, Reports
- CRM (Lite): Dashboard, Leads, Service desk, Bookings, Calendar, Visa, Suppliers, Agents
- Finance (Premium): Accounts, Invoices, Accountants

Wallet, Subscription and Settings leave the sidebar. They live under the profile menu.

## Profile menu and account hub

The avatar menu becomes the MakeMyTrip-style panel: each row has an icon, a title and one line
of description. Rows: My profile (agency details and logo), Wallet (balance, top-ups, statement),
Plan and billing (current plan, compare plans, invoices), Agency settings (Lite and up), Sign out.
A wallet balance pill also sits in the top bar on every page.

`/agency/account`, `/agency/wallet` and `/agency/subscription` keep their URLs (tests, emails and
bookmarks use them) and share one "Account" header with three tabs: Profile, Wallet, Plan and
billing. The Plan tab gets a three-column plan comparison (Basic / CRM Lite / Premium, the
current one marked). Upgrading is a request in this release: the agency clicks "Upgrade" and the
platform admin sets the plan (self-serve plan change with payment is a later step).

## Logo at onboarding and in the profile

- `/apply` gains an optional "Agency logo" upload (PNG, JPG or WebP, up to 1 MB) next to the KYC
  files, stored as a KYC document of type `AGENCY_LOGO`. On approval its storage key becomes the
  tenant's `logo_key` (no file copy; the KYC purge never touches approved applications).
- My profile gets "Upload logo" / "Remove". New `PUT /api/agency/profile/logo` (multipart, owner)
  and `GET /api/agency/profile/logo` (any role of the agency, served with a short cache).
- The logo appears in the sidebar header, the avatar and the home greeting; initials otherwise.

## Platform admin

The admin's Subscription panel offers Basic, CRM Lite, Premium and Custom. QA gets three agencies,
"QA Basic Agency", "QA Lite Agency" and "QA Premium Agency", created through the API by a seed
script, so each plan can be tried side by side.

## Non-goals

- Round-trip and multi-city flight search, cabs, buses, trains.
- Self-serve plan purchase with payment (upgrade is a request in this release).
- Changes to the agent and accountant consoles beyond what plan gating already does.
- Any change to money paths, wallet keys or booking states.

## Calls made by the builder (owner can override)

1. Visa desk sits in CRM Lite (it was not on the sketch).
2. CRM Lite includes 3 staff seats; its price is a placeholder in `sku_prices`.
3. Basic is single-login (owner only): agent management is Lite, accountants are Premium.
4. Client invoicing shows only on Premium (menu), though the server still allows it on Lite.
5. Locked features stay visible with a lock rather than hidden.
6. Upgrade is a request to Voyra, not a payment, in this release.
