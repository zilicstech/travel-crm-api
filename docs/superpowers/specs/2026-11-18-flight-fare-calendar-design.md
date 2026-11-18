# Flight fare calendar: last-seen cheapest fare per route and date, stored in the database

## Context

Agents pick a departure date blind today: the Departure and Return fields in `components/flights/FlightSearchPanel.tsx`
are native `<input type="date">`, and nothing remembers what earlier searches found.
- **Owner idea:** every flight search records the cheapest fare it saw for its route, date and cabin, in the database
  (not in memory, so it survives restarts and is shared by every instance).
- **First search:** the calendar shows nothing for that route.
- **Later searches:** each day shows the least fare the last search for that day found.

**Owner rulings (2026-11-18):**
- **Shared:** one table fed by every agency's searches. It holds a route, a date and a fare, with no agency data.
- **Price shown:** the caller agency's own net fare per adult. That is the same number the results list shows as the
  fare, so it is the published fare less the agency's discount.
- **Age:** a day shows its last-seen fare however old it is, until a newer search overwrites it. Past dates never show.
- **Placement:** a calendar picker on the Departure and Return fields only. No date strip on the results.

## Approaches considered

1. **One shared table of published fares, priced per agency when read (chosen).**
   - The agency's net fare is `PricingMath.displayed(gross, discountPct)` (`AgencyPriceQuoter.price`), which depends only
     on the gross fare. So the cheapest stored gross is also every agency's cheapest net fare, and one row serves all
     agencies.
2. **A row per agency.** It isolates agencies, but the calendar stays empty for small agencies, and the owner ruled it
   out.
3. **The in-memory Caffeine search cache, as today's search result cache.** The owner ruled it out: it is lost on
   restart and differs per instance.

## Design

### Data: public migration `V5__flight_fare_calendar.sql`

The next public number is V5, because V3 is the hotel catalogue and V4 the cancellation requests. The retired-column
drops that the docs call "public V5" move to V6.

```sql
CREATE TABLE public.flight_fare_calendar (
    origin          VARCHAR(3)    NOT NULL,   -- IATA, upper case
    destination     VARCHAR(3)    NOT NULL,
    depart_date     DATE          NOT NULL,
    cabin           VARCHAR(20)   NOT NULL,   -- FlightCabin name
    fare_per_adult  NUMERIC(12,2) NOT NULL,   -- published (gross) fare of the cheapest bookable offer / adults
    searched_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (origin, destination, cabin, depart_date)
);
```

- **Primary key:** its order serves the read, one route and cabin over a date range, as a single range scan.
- **No tenant column and no personal data.** No tenant table may use this name.

### Write: inside `FlightSearchService.searchPage`, on a supplier answer (cache miss) only

- **Which searches count:** only those that can give a comparable per-adult fare. That means all of these:
  - one way (`returnDate == null`);
  - adults only (`children == 0 && infants == 0`);
  - `fareCategory` REGULAR or null;
  - every supplier answered (`failed.isEmpty()`, the same rule the cache uses).

  Round trips, children and infants, special fares and partial answers record nothing.
- **Which fare:** from the priced offers, take the minimum `publishedFare` among offers with `baseFare > 0`, the
  bookable ones. Divide it by `adults`, rounded to 2 places.
- **Searches with no fare:** a search with no such offer writes nothing, and the old value stays.
- **Upsert:** `INSERT … ON CONFLICT (origin, destination, cabin, depart_date) DO UPDATE SET fare_per_adult =
  EXCLUDED.fare_per_adult, searched_at = EXCLUDED.searched_at`. This is "last search's least fare" (owner rule), not
  the all-time minimum.
- **Best effort:** a `DataAccessException` is logged at WARN and swallowed, so a calendar write never fails a search. It
  is one statement on the JdbcTemplate, like `HotelCatalogService`, outside any transaction (`searchPage` has none).
- **Cache hits write nothing,** because nothing new was learnt.
- **Where it lives:** a new `service/FlightFareCalendarService` (`record(query, pricedOffers)` and `read(...)`),
  constructor-injected into `FlightSearchService`, which already takes `registry`, `fanOut` and `quoter`.

### Read: `GET /api/flights/calendar?origin=DEL&destination=BOM&cabin=ECONOMY&from=2026-11-18&to=2027-01-17`

- **Controller:** on the existing `FlightSearchController`. It keeps the same role (`AGENCY_OWNER`, `AGENT`) and is
  already NEVER_GATED (`/api/flights/**`), so `ControllerSkuTable` needs no change.
- **Validation:** IATA codes of 3 letters; `from` ≤ `to`; at most 62 days, which is two months; otherwise 400.
- **Past dates:** `from` is clamped to India's today (`FinancialYear.today()`).
- **Response:** `List<FlightFareDayResponse>` of `{date, fare}`.
  - `fare` is the caller agency's per-adult net fare: `quoter.quoterFor(tenantId, FLIGHT)` applied to the stored fare.
    Only `displayed` is used, which needs no base.
  - With no pricing rules it falls back to the published fare, as `priced()` does today.
  - Days without a row are simply absent.
- **What is never returned:** `searched_at`, the base fare or any agency figure.

### Frontend (`travel-crm`)

- **`lib/api/services/flights.ts`:** a new `fareCalendar(origin, destination, cabin, from, to)` call, sent silently so
  it never blocks the screen. Plus the `FlightFareDay` type in `lib/api/types.ts`.
- **New `components/flights/FareCalendar.tsx`**, a popover opened by a calendar button inside the Departure and Return
  fields.
  - **The date fields stay.** The native date input remains for typing and for the existing unit and e2e tests.
  - **Layout:** a month grid built with `date-fns` (already installed). It shows two months side by side on desktop and
    one on mobile, with Prev and Next.
  - **Each day:** shows a compact price under the number (`₹4.5k`). The cheapest visible day is green, and days before
    `min` are disabled.
  - **Picking a day:** sets the date and closes the popover.
  - **Loading:** fares load for the months on screen when the popover opens or the month changes. A failed load just
    shows no prices.
  - **Accessibility:**
    - each day is a button with an aria-label such as "19 Nov 2026, from ₹4,484 per adult";
    - Escape and outside click close it, through the existing `useDismiss` hook;
    - a footnote reads "Fares from recent searches, per adult; the price is confirmed when you search."
- **Routes:** Departure asks for origin→destination. Return asks for destination→origin, which is the reverse route's
  one-way fares. The button is disabled until both airports are chosen.
- **Shell:** follows `travel-crm/UI_BLUEPRINT.md` (teal brand, `components/ui` primitives).

### Not in this round

- **Round-trip fares:** combined fares are not recorded. The calendar shows one-way fares for both legs.
- **Results:** no date strip on the results.
- **Cleanup:** there is no clean-up job, because past dates are filtered on read. Add a
  `shortcut: rows for past dates stay, delete them in a periodic job if the table grows` comment at the read query.
- **Other dimensions:** no per-supplier or per-environment rows. Each environment has its own database, and the local
  mock fares stay local.

## Files

- **Backend (new):**
  - `src/main/resources/db/migration/public/V5__flight_fare_calendar.sql`
  - `service/FlightFareCalendarService.java`
  - `dto/FlightFareDayResponse.java`
  - tests `FlightFareCalendarServiceTest`, `FlightFareCalendarIT` (Testcontainers: migration, upsert overwrite, range
    read)
- **Backend (edit):**
  - `service/FlightSearchService.java` (constructor and record call on a miss)
  - `controller/FlightSearchController.java` (GET `/calendar`)
  - `FlightSearchServiceTest` / `FlightSearchControllerTest` (record called only for eligible misses; endpoint
    validation)
  - `PROGRESS.md` and root `CLAUDE.md` (bullet; V5 taken, drops become V6)
- **Frontend:**
  - new `components/flights/FareCalendar.tsx` + test
  - edit `FlightSearchPanel.tsx` (button + popover in both date fields) + test
  - `lib/api/services/flights.ts` + test, `lib/api/types.ts`
  - `travel-crm/CLAUDE.md` note
- **Design doc:** `travel-crm-backend/docs/superpowers/specs/2026-11-18-flight-fare-calendar-design.md`, which is this
  design.

## Verification

- **Backend:** `./mvnw clean verify` (100 % coverage, blueprint audit) and `FlightFareCalendarIT` under colima. Unit
  cases:
  - ineligible searches write nothing: round trip, a child, a special fare, a supplier failed, no bookable offer;
  - the minimum published fare among bookable offers, divided by adults;
  - a second search overwrites a cheaper old fare;
  - a DB error is swallowed;
  - the read applies the agency discount and falls back to published without rules;
  - the read skips past dates;
  - 400 on a range over 62 days or bad codes.
- **Frontend:** `npm run test:coverage` (100 %), `tsc`, lint on the changed files.
- **Local journey** (Mock supplier only, no Tripjack):
  1. Search DEL→BOM one-way for a date; the calendar for that route shows nothing before.
  2. Open the Departure calendar again: that day shows the fare equal to the cheapest result per adult.
  3. Search another date and check both show.
  4. Restart the backend and check the fares are still there (from the DB).
  5. Log in as a second agency with a different discount and check it sees its own lower or higher number for the same
     day.
  6. A round trip or a search with a child adds nothing.
- **Pre-merge:** the `regression-tester` (pre-merge) before any push to main.
