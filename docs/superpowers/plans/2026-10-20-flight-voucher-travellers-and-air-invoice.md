# Flight voucher travellers and the air invoice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** E-ticket vouchers carry their travellers and flight legs; the air tax invoice prints like the client's
ePrompt invoice; invoice and booking numbers move to `<PREFIX><8 digits>`.

**Architecture:** Travellers and legs are jsonb snapshots on `booking_document`, copied to `invoice_line_item`
when a draft line is derived; the air PDF is a separate renderer used only when every line has legs. Numbers
change in `DocumentNumberService` and `BookingService.bookingRef`.

**Tech Stack:** Spring Boot 3.3 / Java 17, Flyway, OpenPDF; Next.js 15 / React 19; Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-10-20-flight-voucher-travellers-and-air-invoice-design.md`

## Global Constraints

- Branch `feat/soft-delete-and-accounts`, both repos; nothing committed until the user says so.
- Local `voyra_crm_dev` only (`.claude/run/start-backend.sh`); never the prod DB.
- Every new DTO field has `@Schema` (blueprint audit in `mvn verify`).
- Invoice number: `"%s%08d"` (e.g. `ITI00000001`), counter row `financial_year = 'ALL'`, fresh from 1.
- Booking ref: `"BK%08d"`.
- Rule covers `E_TICKET` vouchers on `FLIGHT` bookings only.

## Review Focus

1. A traveller id from another client — refused with 400, not saved.
2. Inline "Add new traveller" fails (validation) — the voucher form keeps its other inputs and shows the error.
3. Combined invoice with one voucher in AED and one in INR — no foreign total printed, rows still sum.
4. An issued invoice from before this change — reprints in the old layout, unchanged.
5. Booking search for `BK0000004` (prefix) — still matches BK00000042.

---

### Task 1: Numbers

**Files:** `service/DocumentNumberService.java`, `service/BookingService.java:817`,
`repository/spec/BookingSpecifications.java:83-97`; tests `DocumentNumberServiceIT`, `BookingSortTest`, new
`DocumentNumberFormatTest`.

- [ ] `DocumentNumberService.next(kind, asOf)`: for `CREDIT_NOTE` and every kind `forInvoiceCategory` returns
  (plus `TAX_INVOICE`), use `financial_year = 'ALL'` and format `prefix + "%08d"`; others unchanged. Extract
  `static String format(DocumentKind kind, String prefix, String fy, long value)` and unit-test it:
  `format(AIR_INTERNATIONAL_INVOICE, "ITI", "ALL", 1) == "ITI00000001"`, `format(RECEIPT, "RCP", "2026-27", 7) ==
  "RCP/2026-27/0007"`.
- [ ] Invoice FY stays `FinancialYear.of(date)` on the invoice row (reports group by it); only the counter key changes.
- [ ] `bookingRef(n)` = `BK%08d`; `BookingSortTest` updated; search prefix padding 5 → 8; test `BK0000004`
  prefix and `BK-42`.

### Task 2: Voucher travellers and legs (backend)

**Files:** create `db/migration/tenant/V76__voucher_travellers_and_legs.sql`, `dto/VoucherTraveller.java`,
`dto/VoucherLeg.java`, `util/VoucherTrip.java`; modify `entity/BookingDocument.java`,
`dto/BookingDocumentCreateRequest.java`, `dto/BookingDocumentResponse.java`, `service/BookingDocumentService.java`,
`service/BookingService.java` (logBooking, toDocumentResponse), `service/BookingLogService.java`; tests
`VoucherTripTest`, `BookingDocumentServiceTest`.

**Interfaces (produces):**
- `record VoucherTraveller(String memberId, String name, PaxType paxType)` (request uses `memberId` only).
- `record VoucherLeg(String from, String to, String airline, String flightNumber, String cabin, LocalDate travelDate, String departTime, String arriveTime)`.
- `BookingDocument.travellers: List<VoucherTraveller>`, `legs: List<VoucherLeg>` (jsonb).
- `VoucherTrip.check(List<VoucherTraveller>, List<VoucherLeg>)` throws `IllegalArgumentException`:
  "Add the travellers on this ticket" / "Add the flight - from, to and travel date" / "Leg N needs from, to and travel date".
- `VoucherTrip.details(List<VoucherLeg>, Map<String,String> details)` → details with flight keys from legs
  (first leg: flightFrom, airline, flightNumber, flightCabin, journeyDate, flightDepartTime; last leg: flightTo,
  flightArriveTime, flightArriveDate when its date differs from journeyDate).

- [ ] Migration: `booking_document.travellers jsonb, legs jsonb`; `invoice_line_item.travellers jsonb, legs
  jsonb, purchase_currency varchar(3), purchase_rate numeric(14,6)`; backfill legs for E_TICKET vouchers of FLIGHT
  bookings from `details` (journeyDate only when `~ '^\d{4}-\d{2}-\d{2}$'`).
- [ ] add/update voucher: when E_TICKET on FLIGHT, resolve each `memberId` against the booking's client
  (`MemberRepository`, active, same clientId, else 400 "That traveller isn't one of this client's members"),
  fill name and paxType (`PaxTypeCalculator.paxTypeAt(dob, first leg date)`), `VoucherTrip.check`, store, and
  derive details from legs. Other types: travellers/legs ignored (null).
- [ ] logBooking (FLIGHT): voucher travellers from the picked lead members' `memberId`s, legs from the service's
  flight sectors. Not enforced here — the issue check catches a gap.
- [ ] Tests: other client's member refused; no travellers refused; legs → details; non-E_TICKET ignores them.

### Task 3: Invoice lines carry the trip

**Files:** `entity/InvoiceLineItem.java`, `dto/InvoiceLineItemRequest.java`, `dto/InvoiceLineItemResponse.java`,
`service/InvoiceLineSource.java`, `service/InvoiceDocumentService.java`; tests `InvoiceDocumentServiceTest`.

- [ ] Line fields `travellers`, `legs`, `purchaseCurrency`, `purchaseRate`: server-set only (nulled in
  `createDraft` like `charges`), copied in build/toRequest/toLineResponse.
- [ ] `linesFor`: FLIGHT bookings never use `travellerLines`; each voucher line copies the voucher's
  travellers, legs, purchase currency and rate; `describe` uses voucher travellers when present.
- [ ] `issue`: `assertTravellers(lines)` — an E_TICKET voucher of a FLIGHT booking with no travellers →
  409 "Add the travellers for PNR X on the booking's voucher - delete this draft first, then draft it again".
- [ ] Tests: snapshot copied; refusal message.

### Task 4: Air invoice PDF and agency contact

**Files:** create `util/AirInvoicePdfRenderer.java`, public migration `V15__agency_contact.sql`; modify
`entity/Tenant.java`, `dto/AgencyProfile{Response,UpdateRequest}.java`, `service/AgencyProfileService.java`,
`service/InvoiceDocumentService.getPdf`, frontend `app/agency/settings/page.tsx`, `lib/api/types.ts`; test
`AirInvoicePdfRendererTest`.

- [ ] `Tenant.contactPhone, contactEmail, website`; profile read/update; settings inputs.
- [ ] `AirInvoicePdfRenderer.applies(category, lines)` = AIR_INTERNATIONAL/AIR_DOMESTIC and every line has legs
  or is a non-ticket line, with ≥1 legs line. `write(invoice, lines, taxes, agency, logo bytes|null, issuerName)`.
- [ ] Layout per spec "PDF" section. Other = line taxableValue − basic − yq (charges snapshot; a line with no
  charges prints everything in Basic). PAN = GSTIN chars 3–12 when GSTIN is 15 chars. Foreign total only when
  all legs-lines share one non-base purchase currency and rate: ₹ grand total ÷ rate, words via `AmountInWords`.
- [ ] `getPdf` picks the air renderer when `applies`, resolving issuer name from `issuedBy`.
- [ ] Test: two passengers, two legs: text contains "001 ABHISHEK KUMAR SINGH", "002", "DLA ADD", "ET 962",
  "CGST 9%", "SGST 9%", number, and the row totals sum to the gross total.

### Task 5: Voucher form (frontend)

**Files:** create `components/lead/TravellerPicker.tsx`, `components/lead/FlightLegsEditor.tsx`; modify
`components/lead/BookingDocuments.tsx`, `lib/leadActions.ts`, `lib/api/types.ts`,
`components/bookings/BookingServiceFields.tsx` (hide returnDate in voucher mode).

- [ ] E_TICKET on FLIGHT: TravellerPicker (client members as toggle chips; "+ Add new traveller" → name,
  relation (default Other), DOB, gender → `addMember`) and FlightLegsEditor (rows: From, To, Airline, Flight no.,
  Cabin, Date, Departs, Arrives; "+ Add connecting flight"; remove row) replace the flight travel details.
  First leg prefilled from the booking. Save disabled until ≥1 traveller and a complete first leg.
- [ ] Payload sends `travellers: [{memberId}]`, `legs`. Confirm summary shows travellers and legs.
- [ ] Voucher card shows travellers and legs.

### Task 6: e2e and gate

- [ ] `e2e/fixtures.ts` voucher helpers send a traveller (the client's primary member) and one leg; update specs
  that post flight vouchers; `combined-invoice.spec.ts` number regex `/^ITI\d{8}$/`.
- [ ] New `e2e/voucher-travellers.spec.ts`: agent adds an E_TICKET with an existing + new traveller and two legs;
  new traveller on client's members; accountant breaks down, generates; PDF text has both names, both legs,
  CGST/SGST rows and `ITI00000001`-style number; other client's member id → 400.
- [ ] `rtk ./mvnw verify` green; `rtk tsc`, `next build`; recreate Dummy QA tenant; `.claude/run/rebuild-and-test.sh`
  (Playwright + invariants) green.
