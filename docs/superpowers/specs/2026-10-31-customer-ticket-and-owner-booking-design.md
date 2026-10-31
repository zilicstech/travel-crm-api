# Customer e-ticket download and owner bookings without "Issued by"

Date: 2026-10-31. Status: draft for owner approval (builder check, bmad-review and owner answers folded in). Repos:
`travel-crm-backend`, `travel-crm`, branch `nitin-dev`.

## Why

An agency that books a flight through Voyra has nothing to hand its customer. Every consolidator portal produces a
branded e-ticket PDF (the owner shared a sample from Shree Sai Tours), and agencies expect the same: their own name
and logo on top, the airline's logo, the PNR, the flights, the passengers, the fare and the usual travel notes. The
agency usually sells the ticket for more than it paid, so the price printed on the customer's ticket must be the
agency's selling price, never Voyra's figures.

Separately, when the agency owner books for a client, the booking dialog forces them to choose an "Issued by" agent.
The owner wants their own name on their own bookings, and an agent's login to keep working as it does today.

Local development keeps running the mock booking provider (`.claude/run/start-backend-mock.sh`); nothing in this work
calls Tripjack.

## Goals

1. On a ticketed flight platform booking, a **Download ticket** action on the booking page.
2. Clicking it asks whether to add a markup for the client. With a markup the ticket price is the published fare plus
   the markup; without one it is the published fare.
3. The markup is never visible on the ticket: no "markup", "service fee" or similar line (owner ruling). It is folded
   into the price.
4. Downloads are unlimited. Every download is recorded and listed on the booking page; any earlier one can be opened
   again and shows the same price as when it was made.
5. The ticket carries everything in the sample that the system holds (see "Ticket contents").
6. The owner books in their own name: no "Issued by" dropdown, no `ISSUING_AGENT_REQUIRED` error, for flights and
   hotels. Agent bookings keep today's behaviour exactly.

## Non-goals

- No PDF at booking time, no emailing the ticket (the agent downloads and shares it).
- Hotel vouchers: flights only for the ticket. (Part 2 covers hotels too.)
- Meal and seat: the platform books neither, so the ticket has no meal/seat column.
- Passport issue date: never captured at booking, so only the expiry is printed.
- A price below the published fare (passing a discount to the customer): markup is 0 or more.
- The markup is a printing value only: it is not posted to any ledger, invoice, CRM booking or wallet.

## Anti-goals (a failure even if it works)

- The ticket or any ticket endpoint shows the agency's net (displayed fare), commission, Voyra earnings or supplier ids.
- The customer can see the markup as a separate figure.
- Passenger personal data in a stored file that outlives the DPDP purge, or in logs.
- An earlier download reopening at a different price or with different fare lines.
- A passenger printed with another passenger's or another sector's ticket number.
- An agent opening a ticket of a booking they cannot open today.

## Part 1: the e-ticket

### Price

- "Published fare" is the airline's total fare, `platform_booking.gross` (what the agency already sees struck through
  in search). "Base fare" is `platform_booking.base`, the airline's base fare. Owner ruling 2026-10-31: the airline
  base fare may be printed on the customer ticket only; every other agency screen and response still never carries it.
- Ticket total = `gross + markup`. Base fare printed = `base`. Taxes, fees and charges printed = `gross - base + markup`.
- Fare lines: when the stored supplier fare (`itinerary`, the Tripjack review `tripInfos`) gives a readable tax
  breakdown, the named taxes are listed and the last line, "Other charges", is `frozen taxes - sum(named taxes)`, so
  the markup sits there and the lines always add up to the total (as `AirInvoicePdfRenderer` hides markup in "Other").
  If that residual would be negative or the breakdown cannot be read (the mock), one line "Taxes, fees and charges".
  The named lines are frozen with the download (below), so a reopen prints the same lines.
- Markup: rupees, `@DecimalMin 0`, two decimals at most, at most the published fare (`markup <= gross`, same rule and
  message in dialog and API). Blank means 0. For the whole booking, not per passenger.
- A TICKETED row with `gross` or `base` missing, or `base > gross`, cannot produce a ticket (`409 TICKET_NOT_AVAILABLE`).

### Ticket contents

A4 portrait, OpenPDF, in the style of the existing renderers in `util/`, one PDF per download:

1. **Agency header**: logo (`tenant.logo_key`; a missing file or one OpenPDF cannot read, such as WebP, prints the
   name only, never fails the PDF), agency name, `contactEmail`, `contactPhone`, `address`. Read fresh at render,
   like the invoice PDFs. Never Voyra's name or branding.
2. **Booking line**: issued on (`ticketedAt`, IST; `createdAt` only if null), booking id, status in words (Confirmed,
   Cancellation in progress, Cancelled; the booking's current status).
3. **Airline and PNR** per direction: airline logo and name, PNR. A split round trip can have two airlines and two PNRs.
4. **Flights table**, one row per segment of the stored itinerary (`sI`): airline logo, flight number, fare type,
   cabin class, refundable / non-refundable, departure and arrival (date, time, airport code, plus city, terminal and
   airport name when the supplier gave them), duration (the supplier's duration only; never computed from local
   times across time zones). Anything missing prints "-". An itinerary with no segment refuses the ticket (409).
5. **Passengers table**, one row per passenger per direction: name (title, first, last), type, date of birth,
   passport number, nationality, passport expiry, sector (e.g. DEL-BOM), PNR, ticket number, baggage (check-in and
   cabin, from the supplier fare detail). Missing values print "-", never "null".
6. **Fare details**: base fare, taxes, fees and charges (or the lines above), total. INR.
7. **Important information**: a short fixed list (valid photo ID / passport, reach the airport in time, check-in
   closes before departure, web check-in, baggage rules are the airline's, changes and cancellations through the
   agency). Wording is the builder's; no Voyra name.
8. **Watermark** "NOT VALID FOR TRAVEL" across every page when the booking is not TICKETED (cancelled or being
   cancelled), and "TEST BOOKING - NOT VALID FOR TRAVEL" when the row's environment is not PROD (mock, UAT).

### Ticket numbers and PNRs per passenger and sector

Today the provider flattens every traveller's ticket numbers into one list and joins PNRs into one string, so
matching by position is wrong for round trips and multi-sector trips. Change:

- `BookResult` gains structured lines `(paxIndex, sector, pnr, ticketNumber)`, built by
  `TripjackFlightBookingProvider` from each traveller's `pnrDetails` and `ticketNumberDetails` (both keyed by sector),
  and by the mock (one line per passenger per direction).
- Public migration: `platform_booking.ticket_details jsonb` holds them, written where `ticket_numbers` and `pnr` are
  written today. `pnr` and `ticket_numbers` stay as they are for every other reader.
- Rows without `ticket_details` (older bookings, an admin manual resolve, which takes a flat list): ticket numbers by
  position only when the flat list has exactly one per passenger and the trip has one direction, else "-"; PNR is
  `platform_booking.pnr`.

### Downloads are records, not files

Each download stores who, when and the markup, plus the printed fare lines and total frozen at that moment. Opening
a download rebuilds the PDF from the booking plus the frozen lines. No PDF is stored, because the PDF holds passport
data and `PassengerDataPurgeJob` erases passenger data `pax_retention_days` (default 90) after travel.

After the purge, new downloads are refused (`409 PASSENGERS_PURGED`, "Passenger details were removed after travel
under the data-retention rule"); reopening an earlier one prints the ticket with the passenger table replaced by that
sentence.

### Data

Public migration (next after V34):

- `platform_booking.ticket_details jsonb` (above).
- Table `platform_booking_ticket`: `id`, `platform_booking_id` (FK, indexed), `tenant_id`, `seq INT NOT NULL`
  (`UNIQUE (platform_booking_id, seq)`, assigned under the booking row lock, 1, 2, 3...),
  `markup NUMERIC(19,2) NOT NULL CHECK (markup >= 0)`, `fare_lines jsonb NOT NULL` (label and amount per line),
  `total NUMERIC(19,2) NOT NULL`, `created_by`, `created_by_name`, `created_at`. No personal data.

### API (`/api/platform-bookings`, already NEVER_GATED for SKUs)

- `POST /{id}/tickets` `{ markup }`: owner, or an agent who passes the same check as booking detail today
  (`actsOn`); booking must be FLIGHT and TICKETED, else `409 TICKET_NOT_AVAILABLE`; returns the record.
  Allowed for a SUSPENDED agency (it posts no money): `TenantStatusInterceptor` exempts this one path.
- `GET /{id}/tickets`: newest first (owner, agent, accountant): `id, seq, createdAt, createdByName, markup,
  ticketPrice`. No field named `gross`, `base` or `displayed`.
- `GET /{id}/tickets/{ticketId}/pdf`: same roles. Looks the ticket up by ticket id **and** booking id **and** tenant
  after the booking visibility check; any mismatch is 404. Headers: `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff`, `Content-Disposition` filename `e-ticket-<token>-<seq>.pdf` where token is the
  first PNR with non-alphanumerics replaced by `_`, or the booking id.
- Tests: a controller leak test on the list JSON; a cross-booking and a cross-agent ticket id get 404.
  Logging: ids and amounts only.

### Screen (`components/booking/PlatformBookingDetail.tsx`)

- Header button **Download ticket** for a TICKETED flight, owner or agent.
- Dialog: "Add a markup for your client?", one optional rupee field, live "Ticket price: ₹X" (the detail response
  gains `publishedFare`, which the agency already sees in search), **Download** (disabled while the request runs)
  and **Cancel**. Download creates the record, then fetches the PDF and saves it.
- Card **Ticket downloads** under Travellers: number, date and time, by, markup, ticket price, **Open**. Hidden when
  empty. Accountants see the card, not the button.
- Platform admin views show nothing new.

### Airline logos

Copied from `travel-crm/public/airlines/` (70 PNGs, 716 KB) into backend `src/main/resources/airlines/`. A unit test
checks that every airline code the mock emits has a logo. A missing logo prints the airline name only.

## Part 2: owner books in their own name

Owner ruling 2026-10-31: the owner is an agent like any other for bookings; the owner can also manage agents and the
agency. A booking the owner makes is the owner's: their name on it and in reports.

- The owner's user id is the tenant id (as `AuthorResolver` already treats it); the owner's name is `tenant.ownerName`.
- Frontend `BookWithVoyraModal`: remove the "Issued by" select, the `issuedByAgentId` it sends on review and on
  confirm, and the `ISSUING_AGENT_REQUIRED` message.
- Backend: remove `requireIssuingAgent`, `ISSUING_AGENT_REQUIRED` and `resolveIssuer`; remove `issuedByAgentId` from
  `FlightReviewRequest`, `HotelReviewRequest` and `PlatformBookingConfirmRequest` (an old client that still sends it is
  ignored: unknown JSON fields are not an error). The `issued_by_agent_id` column stays as history; nothing writes it.
- Mirror `agentFor`: the CRM booking's agent is always whoever booked (`bookedBy`), owner or agent. The issuer and
  lead-agent fallbacks and the "no agent" error go. An owner booking inside an agent's lead belongs to the owner, so
  that agent does not see it (agents keep seeing only their own platform bookings, as today).
- `BookingService.logBooking` / `resolveOwningAgent`: under `SystemActor`, an agent id equal to the tenant id resolves
  to the owner (`tenant.ownerName`) instead of "Agent not found". Every other caller that looks a booking's
  `agent_id` up in the agent table (invoice raise's owning agent and the like) treats the tenant id as the owner
  rather than failing. Test: an owner books for a client outside any lead and the mirror ends DONE.
- Reports under the owner's name: the bookings report and CRM booking lists already print `agent_name`, so they show
  the owner. The agent performance report (`ReportService.agentsTable`, fed by `AgentService` stats) and the owner
  dashboard's per-agent figures add one row for the owner with the owner's name and their bookings, revenue and leads.
  The bookings list agent filter gets the owner as an option. The Agents management page (logins) does not list the
  owner.
- Owner rename re-syncs `booking.agent_name` (and the invoice equivalent) where `agent_id` is the tenant id, in the
  same transaction, as agent renames already do.
- Platform bookings whose mirror FAILED or was abandoned with the old "needs an issuing agent" error are reset to
  PENDING once by the migration, so the repair job now writes them.
- Effect on Voyra Basic: an owner with no agents can book for a client.
- Regression: the flight, hotel and round-trip e2e specs that pick "Issued by" are updated; PB/PH matrix rows updated.
- Not in this round: an owner creating a CRM booking by hand still picks the agent it belongs to (`BookingService`
  "agentId is required when an Owner creates a booking"). The same rule could apply there later.

## Constraints

- 100 % line, branch and method coverage stays enforced in both repos; new code ships with its tests.
- DPDP: no passenger data in logs, audit notes or stored files; purge rules unchanged.
- Agency surfaces never show Voyra earnings, net, the displayed fare or supplier ids. The airline base fare appears on
  the ticket PDF only (owner ruling); the ticket JSON endpoints never carry it.
- UI follows `travel-crm/UI_BLUEPRINT.md`.
- Never touch the Voyra Global, Global Explorer or Shivansh Test tenants; QA on local QA tenants with the mock.

## Left to the builder

PDF layout and fonts within the existing OpenPDF style; important-information wording; how the mock exposes cabin,
fare type, refundability, baggage and a tax breakdown so the ticket can be tested end to end; the Tripjack field paths
for those (best effort until the slice 0 spike, "-" when absent); test split; e2e for download, reopen and the owner
booking without the dropdown.

## Calls made in this design (owner can override)

1. Downloads are rebuilt from records, not stored files (DPDP); after the purge, no new download.
2. Markup folded into the tax lines ("Other charges" or "Taxes, fees and charges"), never into the base fare.
3. Markup 0 to the published fare; no price below published.
4. Status on the ticket is the booking's current status; cancelled and test bookings are watermarked.
5. Accountants can see and reopen downloads, not create them.
6. A suspended agency can still download tickets.
7. Ticket numbers and PNRs are captured per passenger and sector (new column) instead of guessed by position.
8. The CRM booking always belongs to whoever booked; `issuedByAgentId` is removed from the requests.
9. The owner gets a row in the agent performance report and dashboard figures, and an option in the bookings list
   agent filter, but not on the Agents management page.
10. Airline logos copied into the backend, guarded by a test.
11. Flights only for the ticket; hotel vouchers later.
12. Owner manual CRM bookings keep the agent picker (out of scope).
