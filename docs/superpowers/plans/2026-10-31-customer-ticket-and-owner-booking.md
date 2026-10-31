# Customer e-ticket and owner bookings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agents download a branded customer e-ticket PDF (optional hidden markup, unlimited, listed) for a ticketed
platform flight, and the agency owner books in their own name everywhere with no agent picker.

**Architecture:** Backend renders the PDF on demand with OpenPDF from the platform booking plus a stored download record
(markup and frozen fare lines); no PDF is stored. Ticket numbers and PNRs are captured per passenger and sector in a new
`ticket_details` column. The owner (user id = tenant id) becomes a valid booking owner in `BookingService`, the platform
mirror and reports; frontend pickers and `issuedByAgentId` go.

**Tech Stack:** Spring Boot 3.3 / Java 17, Flyway (public schema), OpenPDF 1.3.39, JUnit 5 + Mockito + MockMvc,
JaCoCo 100 %; Next.js 15 / React 19, Vitest + RTL, Playwright.

**Spec:** `travel-crm-backend/docs/superpowers/specs/2026-10-31-customer-ticket-and-owner-booking-design.md` (approved
2026-10-31). Read it first; this plan does not repeat its reasoning.

## Global Constraints

- Branch `nitin-dev` in both repos, real checkouts (no worktrees). Never push. Commit per task, plain prose, ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01Qq6uXt8Qha7QvtoJUmYRa9`.
- Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"`; never source `.env`.
  `./mvnw verify` must stay green: 100 % lines, branches, methods (JaCoCo) and `scripts/blueprint-audit.sh`.
- Frontend: Vitest thresholds 100 % on all four columns; `npx tsc --noEmit` clean; eslint adds no new problem.
- Migrations: public next is `V35`, then `V36`. Never edit an applied migration.
- Controllers: HTTP, `@Valid`, `@PreAuthorize` only; hand-built response DTOs; services own `@Transactional`.
- No passenger name, passport, email or phone in logs or audit notes (DPDP). Log ids and amounts only.
- Agency JSON never carries `gross`, `base`, `displayed` (on ticket endpoints), commission, `voyra*` or supplier ids.
  The airline base fare appears only inside the PDF. `publishedFare` (= gross) is allowed on the booking detail
  (the agency already sees it in search).
- The markup never appears as a separate line or word on the PDF.
- UI follows `travel-crm/UI_BLUEPRINT.md` and uses `components/ui` primitives.
- QA only on local QA tenants with the mock backend (`.claude/run/start-backend-mock.sh`); never touch Voyra Global,
  Global Explorer or Shivansh Test tenants.

## Review Focus

1. A round trip with two passengers: each passenger row shows that passenger's ticket number for that direction, never
   another's (Task B4 test `twoPaxRoundTripMapsTicketPerPaxAndSector`, Task B5 test `ticketForPaxAndSector`).
2. A reopened download after the airline lines changed shape still prints the same lines and total
   (Task B7 test `reopenPrintsFrozenLines`).
3. An agent guessing a colleague's ticket id under their own booking id gets 404 (Task B7 controller test
   `ticketOfAnotherBookingIs404`, service test `agentCannotOpenColleaguesTicket`).
4. Markup with three decimals, negative, or above the published fare is refused with one message in API and dialog
   (Task B7 `markupRules`, Task F2 `refusesMarkupAbovePublishedFare`).
5. An owner booking for a client with no agents in the agency ends with a CRM mirror DONE under the owner's name
   (Task B2 `ownerBookingMirrorsUnderOwner`).

---

## Backend (`travel-crm-backend`)

### Task B1: The owner owns the CRM bookings they create

**Files:**
- Modify: `src/main/java/com/voyra/crm/service/BookingService.java` (`createBooking` ~144-148, `resolveOwningAgent` ~1215)
- Modify: `src/main/java/com/voyra/crm/service/InvoiceService.java` (`resolveOwningAgentId` ~214)
- Create: `src/main/java/com/voyra/crm/service/OwnerNameSyncService.java`
- Modify: `src/main/java/com/voyra/crm/service/AgencyService.java` (`updateAgency` ~202), `AgencyProfileService.java` (owner-name patch)
- Modify: `src/main/java/com/voyra/crm/dto/BookingCreateRequest.java` (`agentId`: delete if no other reader; grep first)
- Test: `BookingServiceTest`, `InvoiceServiceTest`, new `OwnerNameSyncServiceTest`, `AgencyServiceTest`, `AgencyProfileServiceTest`

**Interfaces:**
- Produces: `resolveOwningAgent(String requestedAgentId) -> AuthorResolver.AuthorInfo` with rules: agent caller ->
  self; `SystemActor.isActive()` -> requested id, where requested == tenant id resolves to
  `AuthorInfo(tenantId, tenant.getOwnerName())`, else the agent lookup as today; real owner caller -> owner AuthorInfo
  (requested id ignored). Task B2 relies on the SystemActor rule.
- Produces: `OwnerNameSyncService.resync(String tenantId, String ownerName)` (`REQUIRES_NEW`, sets and restores
  `TenantContext` in `finally`) running `bookingRepository.updateAgentNameForAgent(tenantId, name)` and the same for
  visas and `leadServiceRepository` assignee name where the id is the tenant id.

- [ ] **Step 1: Failing tests** in `BookingServiceTest`:
  `ownerBookingBelongsToOwner` (owner principal, no agentId -> saved booking `agentId == tenantId`,
  `agentName == "Owner Test"`), `ownerBookingIgnoresRequestedAgent`, `ownerSkipsStandaloneTypeCheck` (owner books
  HOTEL with no agent handling HOTEL -> no exception), `systemActorWithTenantIdResolvesOwner`,
  `systemActorWithAgentIdResolvesAgent`; delete the test pinning "agentId is required when an Owner creates a booking".
  `InvoiceServiceTest.ownerMayNameThemselvesAsInvoiceAgent` (requested id == tenant id -> accepted).
- [ ] **Step 2:** `./mvnw -q -o test -Dtest=BookingServiceTest,InvoiceServiceTest` -> the new tests FAIL.
- [ ] **Step 3: Implement** the rules above. `assertAgentHandlesStandaloneType` runs only for an agent owner id (not for
  the tenant id). In `InvoiceService.resolveOwningAgentId`, a requested id equal to `principal.tenantId()` returns it;
  blank stays an error (owner invoice picking is out of scope).
- [ ] **Step 4: Failing tests** `OwnerNameSyncServiceTest.resyncsBookingVisaAndServiceNames`, and in
  `AgencyServiceTest` / `AgencyProfileServiceTest` `ownerRenameResyncsSnapshots` (verify `resync(tenantId, newName)`
  only when the name changed).
- [ ] **Step 5: Implement** `OwnerNameSyncService` and call it after `setOwnerName` in both services.
- [ ] **Step 6:** run the five test classes -> PASS; `scripts/coverage-gaps.sh 'BookingService*Test,InvoiceService*Test,OwnerNameSync*Test,AgencyService*Test,AgencyProfileService*Test' com.voyra.crm.service` shows nothing missed in the touched classes.
- [ ] **Step 7: Commit** "Owner owns the CRM bookings they create".

### Task B2: Platform bookings belong to whoever booked

**Files:**
- Modify: `service/booking/PlatformBookingService.java` (delete `requireIssuingAgent`, `resolveIssuer`, their calls in `admit`)
- Modify: `service/booking/PlatformBookingMirrorTenantService.java` (`agentFor` ~319; drop `leadAgent`/`hasLeadAgent` if now unused)
- Modify: `dto/FlightReviewRequest.java`, `dto/HotelReviewRequest.java`, `dto/PlatformBookingConfirmRequest.java` (remove `issuedByAgentId`)
- Modify: `entity/PlatformBooking.java` (keep `issuedByAgentId` field, mark `// history: no longer written`)
- Test: `PlatformBookingServiceTest`, `PlatformBookingMirrorTenantServiceTest`, controller tests sending `issuedByAgentId`

**Interfaces:**
- Consumes: B1's SystemActor rule (tenant id -> owner).
- Produces: `agentFor(pb) -> String` = `pb.getBookedBy()`.

- [ ] **Step 1: Failing tests:** `ownerBookingMirrorsUnderOwner` (owner books for a client, no lead, tenant has no
  agents; mirror request carries `agentId == tenantId`; mirror ends DONE), `agentBookingInColleaguesLeadStaysWithAgent`,
  `ownerBookingInAgentsLeadBelongsToOwner`, `ownerClientBookingNeedsNoIssuer` (review + confirm without issuer succeed).
  Delete the `ISSUING_AGENT_REQUIRED` and issuer-ranking tests.
- [ ] **Step 2:** run them -> FAIL.
- [ ] **Step 3: Implement.** Grep `issuedByAgentId` and `ISSUING_AGENT_REQUIRED` across `src/main` and remove every use.
- [ ] **Step 4:** run `PlatformBooking*Test` -> PASS; coverage-gaps on `com.voyra.crm.service.booking` clean.
- [ ] **Step 5: Commit** "Platform bookings belong to whoever booked; Issued by removed".

Deviation from the spec, owner may override: no migration resets old FAILED mirrors. Live bookings are off
(`tripjack_live_ready` false), so only local mock rows can carry the old error, and the repair job retries FAILED rows
below `MAX_ATTEMPTS` anyway.

### Task B3: The owner in the agent leaderboard and export

**Files:**
- Modify: `service/AgentService.java` (new method), `controller/ReportController.java` (`getAgentLeaderboard`), `service/ReportService.java` (`agentsTable`)
- Test: `AgentServiceTest`, `ReportServiceTest`, `ReportControllerTest`

**Interfaces:**
- Produces: `AgentService.leaderboard() -> List<AgentPerformanceResponse>` = `listAgents()` plus one owner row
  (`id = tenantId`, `name = tenant.ownerName`, stats from `loadStats(List.of(tenantId))`, department and commission null),
  sorted as `listAgents` sorts. `/agents` management endpoints unchanged.

- [ ] **Step 1: Failing tests:** `leaderboardAddsOwnerRowWithOwnersBookings`, `agentsExportListsOwner`,
  `agentListStillExcludesOwner`.
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement; leaderboard endpoint and `agentsTable` call `leaderboard()`.
- [ ] **Step 4:** run -> PASS. **Step 5: Commit** "Owner appears in the agent leaderboard and agents export".

### Task B4: Ticket numbers and PNRs per passenger and sector

**Files:**
- Create: `src/main/java/com/voyra/crm/models/booking/TicketLine.java`
- Modify: `models/booking/BookResult.java`, `service/booking/TripjackFlightBookingProvider.java` (~140-158),
  `service/booking/PlatformBookingService.java` (~887), `entity/PlatformBooking.java`
- Create: `src/main/resources/db/migration/public/V35__platform_booking_ticket_details.sql`
- Test: `TripjackFlightBookingProviderTest` (+ fixture under `src/test/resources/tripjack/`), `PlatformBookingServiceTest`

**Interfaces:**
- Produces: `record TicketLine(int paxIndex, String sector, String pnr, String ticketNumber)` (sector like `"DEL-BOM"`,
  any field but paxIndex may be null).
- Produces: `BookResult(..., BigDecimal amount, List<TicketLine> lines)` plus a constructor with the old six parameters
  that passes `List.of()` (so existing callers and the mock compile unchanged).
- Produces: `PlatformBooking.ticketDetails` (`jsonb`, `List<Map<String,Object>>` of TicketLine fields), written next to
  `setTicketNumbers` only when `lines` is not empty.
- Migration: `ALTER TABLE platform_booking ADD COLUMN ticket_details jsonb;`

- [ ] **Step 1: Failing tests:** `twoPaxRoundTripMapsTicketPerPaxAndSector` (fixture: two travellers, `pnrDetails` and
  `ticketNumberDetails` keyed `DEL-BOM` and `BOM-DEL`, one blank ticket -> four lines in traveller order, blank ticket
  number null); `ticketingStoresTicketDetails`; `emptyLinesLeaveTicketDetailsNull`.
- [ ] **Step 2:** FAIL. **Step 3:** implement; the flat `ticketNumbers` and joined `pnr` stay exactly as today.
- [ ] **Step 4:** PASS. **Step 5: Commit** "Capture ticket numbers and PNRs per passenger and sector".

The mock keeps one ticket number per booking (it does not know the passenger count at ticketing); the ticket's flat-list
fallback covers one passenger, and multi-passenger mock tickets print "-". Tripjack fixtures cover the real mapping.

### Task B5: Ticket facts and fare lines (pure)

**Files:**
- Create: `src/main/java/com/voyra/crm/service/booking/ticket/TicketFacts.java`, `TicketFare.java`
- Modify: `service/booking/MockFlightBookingProvider.java` (review itinerary gains fare facts)
- Test: `TicketFactsTest`, `TicketFareTest`, `MockFlightBookingProviderTest`

**Interfaces:**
- Produces: `record FareLine(String label, BigDecimal amount)`;
  `TicketFare.lines(BigDecimal gross, BigDecimal base, BigDecimal markup, Map<String,BigDecimal> namedTaxes) -> List<FareLine>`:
  `[("Base fare", base), named taxes..., ("Other charges", taxes - sum(named))]` where `taxes = gross - base + markup`;
  when `namedTaxes` is empty or the residual is negative, `[("Base fare", base), ("Taxes, fees and charges", taxes)]`.
  `TicketFare.total(gross, markup) -> BigDecimal`. Throws `BookingConflictException("TICKET_NOT_AVAILABLE", ...)` when
  gross or base is null or `base > gross`.
- Produces: `TicketFacts.from(Map<String,Object> itinerary, Map<String,Object> pricingSnapshot)` with
  `List<Segment> segments()` (airlineCode, airlineName, flightNumber, from/to code, city, terminal, airport name,
  departs, arrives, durationMinutes or null), `fareType()`, `cabinClass()`, `Boolean refundable()`,
  `checkInBaggage()`, `cabinBaggage()`, `Map<String,BigDecimal> namedTaxes()` (per pax-type amount times the pax mix in
  `pricingSnapshot.paxMix`; empty when unreadable), `List<String> directions()` (sector keys per direction).
  Reads both shapes already stored: Tripjack `tripInfos[].sI` / `totalPriceList[0].fd.<TYPE>` (`cc`, `rT`, `bI.iB`,
  `bI.cB`, `afC.TAF`), `fareIdentifier`, `sI[].duration`, `da/aa.city|terminal|name`; and the mock's top-level `sI`.
  Anything missing is null.
- Mock: the review itinerary adds `totalPriceList: [{fareIdentifier: "PUBLISHED", fd: {ADULT: {cc: "ECONOMY", rT: 1, bI: {iB: "15 Kg", cB: "7 Kg"}}}}]` and `duration` per segment.

- [ ] **Step 1: Failing tests:** `TicketFareTest`: `markupGoesIntoOtherCharges` (gross 5000, base 4000, markup 500,
  named {YQ: 300} -> Base 4000, YQ 300, Other charges 1200; sum 5500); `noBreakdownGivesOneTaxLine`;
  `negativeResidualFallsBackToOneLine`; `missingBaseRefuses`; `baseAboveGrossRefuses`.
  `TicketFactsTest`: `readsTripjackRoundTripFixture`, `readsMockItinerary`, `unreadableBreakdownIsEmpty`,
  `noSegmentsRefuses` (no `sI` -> `TICKET_NOT_AVAILABLE`), `ticketForPaxAndSector` (helper
  `ticketNumber(int pax, String sector, List<TicketLine> lines, List<String> flat, int paxCount)`: structured match,
  null-sector line matches any sector, else flat list only when `flat.size() == paxCount` and one direction, else "-").
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** PASS, coverage-gaps clean on `service.booking.ticket`.
- [ ] **Step 5: Commit** "Ticket facts and fare lines".

### Task B6: E-ticket PDF renderer and airline logos

**Files:**
- Create: `src/main/java/com/voyra/crm/util/ETicketPdfRenderer.java`
- Create: `src/main/resources/airlines/*.png` (copy of `travel-crm/public/airlines/`)
- Test: `src/test/java/com/voyra/crm/util/ETicketPdfRendererTest.java` (use `PdfText` to read text back),
  `AirlineLogoResourcesTest`

**Interfaces:**
- Consumes: B5 `TicketFacts`, `FareLine`.
- Produces: `ETicketPdfRenderer.render(ETicketData d) -> byte[]` with
  `record ETicketData(String agencyName, String email, String phone, String address, byte[] agencyLogo,
  String bookingId, Instant issuedAt, String statusText, boolean watermarkCancelled, boolean watermarkTest,
  TicketFacts facts, List<PaxRow> passengers /* null = purged */, List<FareLine> fare, BigDecimal total)` and
  `record PaxRow(String name, String type, LocalDate dob, String passport, String nationality, LocalDate passportExpiry,
  String sector, String pnr, String ticketNumber)`. Airline logo read from classpath `airlines/<CODE>.png`.
  Important-information list is fixed copy inside the renderer. Watermark texts: `NOT VALID FOR TRAVEL`,
  `TEST BOOKING - NOT VALID FOR TRAVEL`. Purged text: "Passenger details were removed after travel under the
  data-retention rule".

- [ ] **Step 1: Failing tests:** `printsAgencyAirlineFlightsPassengersAndFare`, `neverPrintsMarkupOrServiceFee` (text
  contains no "markup", "service fee"), `missingValuesPrintDash` (no "null" in text), `unreadableLogoPrintsNameOnly`
  (WebP bytes), `purgedReplacesPassengerTable`, `cancelledAndTestWatermarks`, `noVoyraName` (no "Voyra" in text).
  `AirlineLogoResourcesTest.everyMockAirlineHasALogo`.
- [ ] **Step 2:** FAIL. **Step 3:** implement in the style of `AirInvoicePdfRenderer` (header table, `PdfPTable`s,
  same fonts); unreadable images are skipped inside a small helper, never failing the PDF.
- [ ] **Step 4:** PASS. **Step 5: Commit** "E-ticket PDF renderer with airline logos".

### Task B7: Ticket downloads: records and API

**Files:**
- Create: `src/main/resources/db/migration/public/V36__platform_booking_ticket.sql`
- Create: `entity/PlatformBookingTicket.java`, `repository/PlatformBookingTicketRepository.java`,
  `service/booking/PlatformBookingTicketService.java`, `dto/TicketCreateRequest.java`, `dto/TicketDownloadResponse.java`
- Modify: `controller/PlatformBookingController.java`, `security/TenantStatusInterceptor.java`,
  `dto/PlatformBookingResponse.java` + `PlatformBookingQueryService.agencyViews` (`publishedFare`)
- Test: `PlatformBookingTicketServiceTest`, `PlatformBookingControllerTest`, `TenantStatusInterceptorTest`,
  `PlatformBookingQueryServiceTest`

**Interfaces:**
- Migration V36: table `platform_booking_ticket` (`id VARCHAR(36) PK`, `platform_booking_id VARCHAR(36) NOT NULL
  REFERENCES platform_booking(id)`, `tenant_id VARCHAR(36) NOT NULL`, `seq INT NOT NULL`, `markup NUMERIC(19,2) NOT NULL
  CHECK (markup >= 0)`, `fare_lines jsonb NOT NULL`, `total NUMERIC(19,2) NOT NULL`, `created_by VARCHAR(36) NOT NULL`,
  `created_by_name VARCHAR(160)`, `created_at TIMESTAMPTZ NOT NULL`, `UNIQUE (platform_booking_id, seq)`), index on
  `platform_booking_id`.
- `TicketCreateRequest { @DecimalMin("0") @Digits(integer = 9, fraction = 2) BigDecimal markup }` (null -> 0); the
  service also refuses `markup > gross`: 400 "Markup can be at most the published fare".
- `TicketDownloadResponse { String id; int seq; Instant createdAt; String createdByName; BigDecimal markup; BigDecimal ticketPrice; }`
- `PlatformBookingTicketService`: `create(String bookingId, BigDecimal markup) -> TicketDownloadResponse` (visibility
  as `PlatformBookingQueryService.get`; FLIGHT and TICKETED else 409 `TICKET_NOT_AVAILABLE`; `pax_purged_at` set ->
  409 `PASSENGERS_PURGED`; locks the booking row (`findByIdForUpdate`), `seq = max + 1`; freezes `TicketFare.lines`);
  `list(String bookingId) -> List<TicketDownloadResponse>` newest first; `pdf(String bookingId, String ticketId) ->
  TicketPdf(byte[] bytes, String filename)` (ticket looked up by id + booking id + tenant after the visibility check,
  else 404; decrypts `pax_enc` with the existing codec; passengers null when purged; agency logo through the same
  helper `InvoiceDocumentService` uses at ~801; status text Confirmed / Cancellation in progress / Cancelled;
  filename `e-ticket-<first PNR, non-alphanumerics -> _, or booking id>-<seq>.pdf`).
- Controller: `POST /{id}/tickets` (`AGENCY_OWNER`, `AGENT`), `GET /{id}/tickets`, `GET /{id}/tickets/{ticketId}/pdf`
  (class roles) with headers `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition`
  attachment.
- `TenantStatusInterceptor`: a SUSPENDED agency may `POST /api/platform-bookings/{id}/tickets` (path pattern match);
  every other write keeps its 423.

- [ ] **Step 1: Failing tests:** service: `createsFirstAndSecondDownload` (seq 1, 2; total = gross + markup),
  `refusesNotTicketed`, `refusesHotel`, `refusesPurged`, `markupRules` (negative, 3 decimals via validation in the
  controller test; above gross in the service), `reopenPrintsFrozenLines` (change the itinerary after create; the PDF
  still prints the frozen lines), `agentCannotOpenColleaguesTicket`, `purgedReopenHasNoPassengers`, `logsNoPassengerData`
  (`support/LogCapture` at INFO; no first name or passport in output).
  Controller: `listJsonHasNoVoyraFigures` (no `gross`, `base`, `displayed`, `commission`, `voyra` keys),
  `ticketOfAnotherBookingIs404`, `pdfHeaders`, `accountantCannotCreate` (403), `accountantCanList`.
  Interceptor: `suspendedMayDownloadTicket`, `suspendedOtherWriteStill423`.
  Query: `detailCarriesPublishedFare`.
- [ ] **Step 2:** FAIL. **Step 3:** implement; classify nothing new in `ControllerSkuTable` (same controller, already
  NEVER_GATED); confirm `scripts/blueprint-audit.sh` passes.
- [ ] **Step 4:** `./mvnw -q -o verify` -> BUILD SUCCESS (100 % gate, audit COMPLIANT).
- [ ] **Step 5: Commit** "Ticket downloads: records, PDF endpoint, suspended agencies may print".

## Frontend (`travel-crm`)

### Task F1: No agent picker for the owner

**Files:**
- Modify: `components/booking/BookWithVoyraModal.tsx` (Issued by select ~381, `issuedByAgentId` at ~229 and ~280,
  `ISSUING_AGENT_REQUIRED` message), `components/flights/QuickBookFlightModal.tsx`, `components/hotels/QuickBookHotelModal.tsx`,
  `components/shared/BookingFormModal.tsx`, `components/lead/ServiceBookings.tsx` (`needsAgent`, picker ~385),
  `components/bookings/BookingsList.tsx` (Issued by filter: owner option, as "Assign by" does with `ownerId`/`ownerLabel`),
  `lib/api/types.ts` (`issuedByAgentId`, `agentId` on the create request if B1 removed it)
- Test: each component's existing `*.test.tsx`

- [ ] **Step 1: Failing tests:** `ownerSeesNoIssuedBy` / `ownerSeesNoAgentPicker` in each modal test (no
  "Issued by" / agent combobox; Book enabled with a client chosen; request body has no `issuedByAgentId` / `agentId`);
  `BookingsList` `issuedByFilterOffersOwner`. Delete tests that pinned the picker.
- [ ] **Step 2:** `npx vitest run <those files>` -> FAIL. **Step 3:** implement (remove code; `AgentPicker` stays for
  other screens). **Step 4:** PASS; coverage 100 % on the touched files; `npx tsc --noEmit` clean.
- [ ] **Step 5: Commit** "Owner books without picking an agent".

### Task F2: Download ticket on the platform booking page

**Files:**
- Modify: `lib/api/services/platformBookings.ts` (+ test), `lib/api/types.ts`, `components/booking/PlatformBookingDetail.tsx` (+ test)
- Create: `components/booking/TicketDownloadDialog.tsx` (+ test), `components/booking/TicketDownloadsCard.tsx` (+ test)

**Interfaces:**
- Consumes: B7 endpoints and `TicketDownloadResponse`; `PlatformBookingResponse.publishedFare: number | null`.
- Produces: `createTicket(id: string, markup: number): Promise<TicketDownload>`,
  `listTickets(id: string): Promise<TicketDownload[]>`, `downloadTicketPdf(id: string, ticketId: string): Promise<void>`
  (fetch blob with auth, save via an object URL and the filename from `Content-Disposition`; follow how other PDF
  downloads in `lib/api` do it).

- [ ] **Step 1: Failing tests:** dialog: `showsLiveTicketPrice` (published 5000, type 500 -> "Ticket price: ₹5,500"),
  `blankMarkupMeansPublished`, `refusesMarkupAbovePublishedFare` (message "Markup can be at most the published fare",
  Download disabled), `downloadCreatesThenFetches` (calls `createTicket` then `downloadTicketPdf`; button disabled while
  pending), `showsApiError`. Card: `listsDownloadsNewestFirst`, `openFetchesThatTicket`, `hiddenWhenEmpty`.
  Detail: `downloadButtonOnlyForTicketedFlight`, `accountantSeesCardNotButton`.
  Service: request shapes and error mapping.
- [ ] **Step 2:** FAIL. **Step 3:** implement with `components/ui` primitives (Modal, Button, Card, Input).
- [ ] **Step 4:** PASS; 100 % coverage on the new and touched files; tsc and eslint clean.
- [ ] **Step 5: Commit** "Download customer e-ticket from the platform booking page".

## Task E: End to end, docs and regression

**Files:**
- Modify: `travel-crm/e2e/platform-flight-booking.spec.ts` (~230-233), `platform-hotel-booking.spec.ts` (~219),
  `platform-flight-roundtrip.spec.ts` (~125-137): drop `issuedByAgentId`; assert the owner booking's CRM mirror has the
  owner's name.
- Create: `travel-crm/e2e/platform-ticket.spec.ts`: QA owner books a mock flight for a client (UI, no agent picker),
  downloads a ticket with markup 500 (dialog), the downloads card shows #1 and ₹(published + 500), downloads again
  without markup (#2, published), Open #1 returns a PDF (`content-type: application/pdf`); an agent of the same agency
  gets 404 on that ticket's PDF URL.
- Modify: root `CLAUDE.md` (slice 3 note "owner booking ... needs an Issued-by agent" replaced; ticket endpoints and
  `ticket_details`), `travel-crm/CLAUDE.md`, `travel-crm-backend/PROGRESS.md`,
  `.claude/skills/full-regression/scenario-matrix.md` (rows for ticket download, owner booking without picker, suspended
  agency ticket), memory `b2b_platform_plan.md`.

- [ ] **Step 1:** build the jar, restart with `.claude/run/start-backend-mock.sh`; run the four specs with
  `npx playwright test <files> --timeout 60000` -> all pass.
- [ ] **Step 2:** full gates: `.claude/run/fb-gates.sh` (backend verify, ITs via colima, tsc, lint, build, Playwright,
  invariants). Expected: verify exit 0; Playwright no new failures against the last run (192/194, the 2 known);
  invariants hold.
- [ ] **Step 3:** manual Chrome pass on `http://localhost:3000` as QA Premium owner and agent: book, download with and
  without markup, open an earlier download, check the PDF visually against the sample.
- [ ] **Step 4: Commit** docs in each repo. Do not push; regression-tester runs before any PR.
