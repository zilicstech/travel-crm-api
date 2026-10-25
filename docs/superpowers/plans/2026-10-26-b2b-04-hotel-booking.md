# B2B Platform — Slice 4: Hotel booking end to end — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agencies book hotels through Voyra exactly as they book flights in slice 3 — priced room options with "You earn", a Book with Voyra wizard, wallet hold → single supplier book → debit + commission, poll/UNKNOWN/resolve, whole-booking cancellation with the supplier's cancellation policy, a read-only CRM mirror with a `HOTEL_VOUCHER`, and the same Platform Bookings screens.

**Architecture:** Reuse slice 3 end to end. `PlatformBookingService`, the poller, cancellation, the mirror and the screens become product-aware (`product = FLIGHT | HOTEL`) by dispatching supplier calls to a `BookingProviderRegistry` that returns the `FlightBookingProvider` or a new `HotelBookingProvider` for a row's product. No second state machine, no second set of keys: the wallet keys, statuses, events, guards and invariants are product-neutral already.

**Tech Stack:** as slice 3.

**Spec:** `/Users/shivanshsrivastava/Projects/Voyra global/B2B_PLATFORM_DESIGN.md` §5.1 ("Hotels: identical engine, base = room rate excl. taxes"), §7.4, §7.5, §9, §12. Slice 3 plan `docs/superpowers/plans/2026-10-25-b2b-03-flight-booking.md` and its rulings section are binding context.

**Deviations (rulings):**
- Slice 0 not done for hotels either: the Tripjack hotel booking provider uses the existing `/hms/v3/hotel/pricing` option as review and best-effort `/hms/v3/hotel/review`, `/oms/v3/hotel/book`, `/oms/v3/hotel/booking-details`, `/oms/v3/hotel/cancel-booking` shapes, all marked unverified; the mock is the default (`app.hotel-booking.provider=mock`).
- Hotel base fare: the room rate excluding taxes when the supplier gives it; otherwise the option is `bookable=false` with `BASE_FARE_UNAVAILABLE` (same rule as flights — never price on base 0).
- Guests: one lead guest per room is required (name, title); other guests optional names. PAN of the lead guest is captured when the supplier marks it mandatory (Indian hotels) — the mock never requires it.
- Hotel cancellation refund comes from the supplier quote (policy-based); the slice 3 refund-unknown rules apply unchanged.

## Global Constraints

- All slice 1–3 Global Constraints hold. Next public migration is the next free `V2x` after slice 3; no tenant migration expected.
- `PricingProduct.HOTEL` rows drive hotel pricing; no row → `PRICING_NOT_CONFIGURED`.
- Who may book hotels: owner, or AGENT whose `manageableServices` contains `HOTEL`.
- Agency views never show Voyra figures, supplier ids or raw policy payloads beyond the human-readable cancellation policy.

## Review Focus

1. A room option repriced between hotel detail and review — re-confirm required. Task 2.
2. Hotel cancellation after the free-cancellation deadline — the quoted refund (possibly 0) is what's posted; the recall still applies. Task 3.
3. A flight booking and a hotel booking for the same agency polled in the same run — each goes to its own provider. Task 2.
4. Mirror for a hotel creates a `HOTEL_VOUCHER` with check-in/check-out and hotel name, not an `E_TICKET`. Task 3.
5. Agent with FLIGHT only tries to book a hotel → 403. Task 2.

---

### Task 1: Hotel booking provider (mock + Tripjack) and priced room options

- `service/booking/HotelBookingProvider` (review(optionId, hotelId, checkIn, checkOut, rooms) → `FareReview` incl. `cancellationPolicy` text and `freeCancellationUntil`; book; bookingDetails; cancelQuote; cancel), `MockHotelBookingProvider` (same switches as the flight mock under `app.hotel-booking.mock.*`; base = 82 % of total; refund 100 % before a mock deadline of check-in − 2 days, else 0), `TripjackHotelBookingProvider` (unverified shapes, defensive parsing, timeouts as slice 3).
- `HotelSearchService`/`HotelDetailResponse.RoomOption` and `HotelOfferResponse` gain `publishedFare`, `displayedFare`, `agencyEarns`, `bookable`, `notBookableReason` via `AgencyPriceQuoter.quote(tenant, HOTEL, gross, base)`; base kept internal (`@JsonIgnore`); priced results cached per tenant.
- Tests: provider tests (mock switches, Tripjack request/response shapes, missing base → null), priced detail test, raw JSON has no `voyraEarned`/`voyraNet`/`baseFare`.

### Task 2: Product-aware booking service, poller and APIs

- `BookingProviderRegistry` (`supplierFor(PlatformBooking)` / `forProduct(PricingProduct)`); `PlatformBookingService` review gains `reviewHotel(HotelReviewRequest{hotelId, optionId, checkIn, checkOut, rooms:[{adults, childAges[]}], clientId?, leadId?, serviceId?, searchDisplayedFare?, display hints hotelName, city, roomName, mealBasis})`; confirm/abort/poll/cancel dispatch through the registry; `PaxInput` gains optional `roomIndex` and `pan`; HOTEL permission check; itinerary JSON for hotels stores `{hotel:{name, city, address}, stay:{checkIn, checkOut, nights}, rooms:[…], cancellationPolicy}`.
- `PlatformBookingController` gains `POST /hotels/review`; responses carry `product` and a hotel summary; query service renders hotel summaries.
- Tests: Review Focus 1, 3, 5; confirm/poll/cancel for a hotel booking reuse the flight paths (one test each through the registry).

### Task 3: Hotel cancellation policy and mirror

- Cancel quote shows the policy text and the deadline; refund from the supplier quote (unknown → slice 3 rules).
- Mirror for HOTEL: CRM booking type hotel, one `HOTEL_VOUCHER` document (hotel name, city, check-in/out, rooms, meal basis, confirmation number), purchase = displayed, same VOYRA_WALLET bill posting to 1310, same guards; mirror cancel via the existing cancel.
- Tests: Review Focus 2 and 4; mirror cancel for a hotel.

### Task 4: Frontend — priced room options and the hotel wizard

- `HotelDetail` room options: published struck through, displayed, "You earn", **Book with Voyra** (Quick book stays); `HotelSearchPanel` list shows "from ₹X" displayed.
- `BookWithVoyraModal` hotel mode: rooms with a lead guest each (title, name, PAN when required), optional other guests, client picker + issued-by as flights, policy + free-cancellation deadline on the review step, confirm/status as flights. Lead service tab hotel search passes leadId/serviceId/client.
- Platform Bookings screens: product column/icon, hotel summary on list and detail, policy on cancel modal.
- tsc / lint (22) / build; Chrome: owner books one hotel end to end on the mock; agent with HOTEL books one with a client (mirror visible in CRM with the Voyra badge); 390px.

### Task 5: E2E, invariants, docs

- `e2e/platform-hotel-booking.spec.ts` (pricing HOTEL row for Dummy QA; owner books → TICKETED/CONFIRMED with confirmation number; statement rows; cancel → refund + recall; agent with client → mirror with HOTEL_VOUCHER; agent without HOTEL → 403).
- invariants: booking invariants already product-neutral — add a HOTEL row to the checks; matrix PH rows; docs + PR texts (slice 4 section, `HOTEL_BOOKING_PROVIDER` env).
- Gates: `./mvnw verify`, full Playwright on a fresh production build, invariants hold.
