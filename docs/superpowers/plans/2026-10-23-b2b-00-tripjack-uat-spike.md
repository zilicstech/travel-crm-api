# B2B Platform — Slice 0: Tripjack UAT spike (manual, one week)

Not an implementation plan — a checklist the owner runs with the Tripjack UAT key (IP-whitelisted
machine) while Slice 1 is built. Output feeds Slices 3–4 (`B2B_PLATFORM_DESIGN.md` §7.3–7.5).
Store every raw request/response as JSON under `travel-crm-backend/src/test/resources/tripjack/uat/`
with the API key redacted; they become `MockRestServiceServer` fixtures.

## Commercials (ask Tripjack account manager)
- [ ] What Tripjack pays Voyra per ticket: commission on base fare, fixed incentive, net-fare only, or a mix; per airline/sector table if any.
- [ ] Whether the fare in the API carries it (field name, per fare or per PNR) or only the monthly statement does.
- [ ] Deposit terms: minimum balance, top-up method, settlement cycle, hotel deposit separate or shared.
- [ ] Their cancellation/amendment fee schedule for flights and hotels.
- [ ] Production go-live requirements (self-certification steps, IP list, SLA for support).

## Flights — one domestic FSC, one domestic LCC, one international
- [ ] `POST /fms/v1/air-search-all` — record; note the base fare field (`BF`?) and tax breakdown per pax type.
- [ ] `POST /fms/v1/review` — record; note the booking/review id field, repriced total, time-to-live of the review.
- [ ] `POST /fms/v2/farerule` — record.
- [ ] `POST /oms/v1/air/book` — record; confirm `paymentInfos.amount` must equal the reviewed total to the paisa; record the `success:false` + fare-expired shape by reviewing, waiting past TTL, then booking.
- [ ] `POST /oms/v1/booking-details` — poll every 10 s and log how long each booking stays pending before TICKETED (LCC especially); record the FAILED shape.
- [ ] `POST /oms/v1/air/amendment/submit-amendment` — cancel one ticket; record the refund figure fields and the amendment status polling shape.
- [ ] Confirm whether partial-pax cancellation is supported in UAT and what it returns.
- [ ] Note any endpoint whose path differs from the above — correct `B2B_PLATFORM_DESIGN.md` §7.3.

## Hotels — one domestic, one international
- [ ] `/hms/v3/hotel/listing` and `/hms/v3/hotel/pricing` (exist) — record.
- [ ] Hotel review/book/booking-details/cancel endpoints — find the exact `/hms/v3/…` or `/oms/v1/hotel/…` paths in the Tripjack hotel doc, run each, record.
- [ ] Cancellation policy fields in the review response.

## Outputs
- [ ] Fixture files committed (redacted).
- [ ] A one-page `docs/superpowers/specs/tripjack-uat-findings.md`: endpoint table, pending-time histogram → hold timeout, commission exposure answer, partial-cancel answer.
- [ ] `platform_setting.reference_fare_basket` replaced with 5–10 real fares (gross + base) from the recordings.
