# Tripjack Hotel Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** When `HOTEL_SUPPLIER=tripjack`, the hotel search inside a lead's Hotel service returns real Tripjack offers. It reuses the agency's Tripjack credential from Settings › Supplier Integration, the same as flights.

**Architecture:** `TripjackHotelSearchProvider implements HotelSearchProvider`, behind `app.hotel-supplier.provider=tripjack`. No caller changes: the controller, the proposal flow and the modal stay as they are.

**Contract (Tripjack Hotel API v3).** Tripjack doesn't publish this openly. It is taken from a public, live TripJack v3 integration (repo `hussainhabeebi/poomas`, `packages/suppliers/src/tripjack/hotel-v3.ts`).
- **Hosts:** UAT `https://apitest-hms.tripjack.com`, production `https://hms-search.tripjack.com`. These are separate from the flight host.
- **Auth:** the `apikey` header, the same key as flights.
- **City to hotels:** search takes Tripjack hotel ids, not a city. The flow is:
  1. `GET /hms/v3/content/fetch-city-regionIds?limit=2000&cursor=` (paged) gives `hotelCityRegionIds[{cityName, cityRegionId, countryName}]`, `nextCursor`, `hasMore`.
  2. `POST /hms/v3/content/fetch-hotel-mapping {regionIds, page, size}` gives `hotels[{tjHotelId}]`.
- **Nationality:** `GET /hms/v3/nationality-info` gives `nationalityInfos[{countryId, code}]`. The default is IN.
- **Listing:** `POST /hms/v3/hotel/listing`.
  - Request: `{checkIn, checkOut, rooms:[{adults, children, childAge[]}], currency:"INR", correlationId, nationality, hids:[number ≤100]}`.
  - Response: `hotels[{tjHotelId, name, options[{optionId, roomInfo[{name}], mealBasis, pricing{totalPrice}, cancellation{isRefundable, penalties[{from,to,amount}], deadlineDateTime}}]}]`.
- **Content:** `POST /hms/v3/content/fetch-hotel-content {hotelIds ≤100}` gives `hotels[{tjHotelId, star_rating, locale.address.fulladdr}]`, used for stars and address. It is best effort: a failure keeps the listing.

## Decisions (builder's calls)
- **Caching:** the city index is held in memory per host and rebuilt after 7 days. Hotel ids per region set are cached for 1 day, nationality ids for 7 days, and content per hotel for 7 days. All of these are static Tripjack data, the same for every agency, so nothing tenant-specific is cached. The first search after a restart pages through the whole city list once, so it is slow.
- **Results:**
  - one offer per hotel at its cheapest option, sorted by price;
  - at most 3 listing batches (300 hotels);
  - at most 60 offers returned.
- **Rooms:** adults are spread evenly across the rooms, and children likewise. Child ages are not asked in the modal today, so each child is sent as 8 years old. The query gains optional `childAges` for later.
- **Country:** the query gains an optional `countryCode` (ISO2, which `CityPicker` already gives). It narrows a city name that exists in more than one country. There are aliases for the common Indian renames (Bangalore/Bengaluru, …).

## Tasks
1. **Backend:**
   - add `HotelSearchQuery.countryCode` and `childAges`;
   - add `TripjackHotelSearchProvider` and its test (MockRestServiceServer covering the full flow and the mapping, the unknown-city error, and the error envelope);
   - update the `application.properties` comment.
2. **Frontend:** `HotelSearchModal` sends `countryCode` from `CityPicker`.
3. **Verify:** `mvn verify` and `tsc`; then a live start with `HOTEL_SUPPLIER=tripjack` on the local DB, where a search reaches Tripjack, or fails with a clear Tripjack message if the IP is not whitelisted.

## Not in scope
Hotel booking, pricing, review or hold on Tripjack. Search only, the same as flights.
