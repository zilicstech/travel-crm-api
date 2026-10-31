package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.PlatformAdminBookingResponse;
import com.voyra.crm.dto.HotelStaySummary;
import com.voyra.crm.dto.PlatformBookingResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformBookingQueryServiceTest {

    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformBookingRepository bookings;
    @Mock private PlatformBookingEventRepository events;
    @Mock private TenantRepository tenants;
    @Mock private AgentRepository agents;
    @Mock private ClientRepository clients;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);

    private PlatformBookingQueryService service() {
        return new PlatformBookingQueryService(bookings, events, tenants, agents, clients, paxCodec, objectMapper);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String userId, UserType type, String tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@x.test", type, tenantId), null));
    }

    private PlatformBooking booking(String id, String tenantId, String bookedBy) {
        return PlatformBooking.builder().id(id).tenantId(tenantId).product(PricingProduct.FLIGHT)
                .status(PlatformBookingStatus.TICKETED).environment(SupplierEnvironment.UAT)
                .supplierReviewId("RV-SECRET").supplierBookingId("TJS-SECRET").pnr("ABC123").ticketNumbers(List.of("098-1"))
                .gross(new BigDecimal("5000.00")).base(new BigDecimal("4250.00")).discountPct(new BigDecimal("10.00"))
                .displayed(new BigDecimal("4500.00")).voyraEarned(new BigDecimal("250.00"))
                .voyraEarnedSource(VoyraEarnedSource.ESTIMATE).agencyCommission(new BigDecimal("100.00"))
                .tds(new BigDecimal("5.00")).voyraNet(new BigDecimal("145.00")).paxCount(1)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(Map.of("type", "ADULT", "title", "Mr", "firstName", "Rahul",
                        "lastName", "Sharma", "passportNo", "N1234567")), "contactEmail", "c@x.test")))
                .itinerary(Map.of("tripInfos", List.of(Map.of("sI", List.of(Map.of("fD", Map.of("aI", Map.of("code", "6E", "name", "IndiGo"),
                        "fN", "201"), "da", Map.of("code", "DEL"), "aa", Map.of("code", "BOM"),
                        "dt", "2026-12-01T06:00", "at", "2026-12-01T08:10", "id", "SEG-SECRET"))))))
                .mirrorStatus(MirrorStatus.DONE).tenantBookingId("TB1").bookedBy(bookedBy).clientId("C1")
                .createdAt(Instant.parse("2026-10-25T05:00:00Z")).build();
    }

    private static PlatformBookingEvent event(String type) {
        return PlatformBookingEvent.builder().id("E-" + type).platformBookingId("B1").type(type)
                .payload(Map.of("voyraNet", "145.00")).createdAt(Instant.parse("2026-10-25T05:00:00Z")).build();
    }

    @Test
    void agencyViewCarriesNamesAndPlainEventsButNoVoyraMoneyOrSupplierIds() throws Exception {
        login("O1", UserType.AGENCY_OWNER, "T1");
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("B1", "T1", "O1")));
        given(agents.findAllById(List.of("O1"))).willReturn(List.of(Agent.builder().id("O1").name("Olga Owner").build()));
        given(clients.findAllById(List.of("C1"))).willReturn(List.of(com.voyra.crm.entity.Client.builder().id("C1").name("Acme Travel").build()));
        given(events.findByPlatformBookingIdOrderByCreatedAtAsc("B1"))
                .willReturn(List.of(event("HELD"), event("VARIANCE"), event("TICKETED")));

        PlatformBookingResponse r = service().get("B1");
        String json = objectMapper.writeValueAsString(r);

        for (String forbidden : List.of("voyraEarned", "voyraNet", "gross", "supplierReviewId", "supplierBookingId",
                "RV-SECRET", "TJS-SECRET", "SEG-SECRET", "N1234567", "145.00", "environment", "credential")) {
            assertThat(json).as(forbidden).doesNotContain(forbidden);
        }
        assertThat(r.getBookedByName()).isEqualTo("Olga Owner");
        assertThat(r.getClientName()).isEqualTo("Acme Travel");
        assertThat(r.getAgencyEarns()).isEqualByComparingTo("95.00");
        assertThat(r.getPassengers()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("Mr Rahul Sharma");
            assertThat(p.getType()).isEqualTo("ADULT");
        });
        assertThat(r.getItinerary()).singleElement().satisfies(s -> {
            assertThat(s.getFrom()).isEqualTo("DEL");
            assertThat(s.getTo()).isEqualTo("BOM");
            assertThat(s.getFlightNumber()).isEqualTo("6E-201");
        });
        assertThat(r.getTimeline()).extracting(l -> l.getText())
                .containsExactly("Wallet amount reserved", "Ticket issued");
    }

    @Test
    void detailCarriesPublishedFare() {
        login("O1", UserType.AGENCY_OWNER, "T1");
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("B1", "T1", "O1")));

        PlatformBookingResponse r = service().get("B1");

        assertThat(r.getPublishedFare()).isEqualByComparingTo("5000.00");
        assertThat(r.getDisplayedFare()).isEqualByComparingTo("4500.00");
    }

    /** R19: an owner's booking is booked by the owner (user id = tenant id), named from the agency, never "-". */
    @Test
    @SuppressWarnings("unchecked")
    void anOwnersBookingNamesTheOwner() {
        login("T1", UserType.AGENCY_OWNER, "T1");
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("B1", "T1", "T1")));
        given(tenants.findAllById(List.of("T1"))).willReturn(List.of(Tenant.builder().id("T1").agencyName("Acme Travel").ownerName("Dana Owner").build()));

        assertThat(service().get("B1").getBookedByName()).isEqualTo("Dana Owner");

        login("SA1", UserType.SUPER_ADMIN, null);
        given(bookings.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .willReturn((Page<PlatformBooking>) new PageImpl<>(List.of(booking("B1", "T1", "T1"))));
        assertThat(service().adminList(0, 25, null, null).getContent().get(0).getBookedByName()).isEqualTo("Dana Owner");
    }

    @Test
    void aPurgedBookingStillLoadsWithNoPassengers() {
        login("O1", UserType.AGENCY_OWNER, "T1");
        PlatformBooking purged = booking("B1", "T1", "O1");
        purged.setPaxEnc(null);
        purged.setPaxPurgedAt(Instant.now());
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(purged));

        assertThat(service().get("B1").getPassengers()).isEmpty();
    }

    @Test
    void anotherAgencysBookingIs404() {
        login("O2", UserType.AGENCY_OWNER, "T2");
        given(bookings.findByIdAndTenantId("B1", "T2")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().get("B1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAgentCannotOpenAColleaguesBooking() {
        login("A1", UserType.AGENT, "T1");
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("B1", "T1", "A2")));

        assertThatThrownBy(() -> service().get("B1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void agentListIsScopedToTheirOwnBookings() {
        login("A1", UserType.AGENT, "T1");
        given(bookings.findByTenantIdAndBookedBy(eq("T1"), eq("A1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(booking("B1", "T1", "A1"))));

        PagedResponse<PlatformBookingResponse> page = service().list(0, 25);

        assertThat(page.getContent()).hasSize(1);
        verify(bookings).findByTenantIdAndBookedBy(eq("T1"), eq("A1"), any(Pageable.class));
    }

    @Test
    void ownerAndAccountantListTheWholeTenant() {
        for (UserType type : List.of(UserType.AGENCY_OWNER, UserType.ACCOUNTANT)) {
            login("U1", type, "T1");
            given(bookings.findByTenantId(eq("T1"), any(Pageable.class))).willReturn(new PageImpl<>(List.of(booking("B1", "T1", "A1"))));

            assertThat(service().list(0, 25).getContent()).hasSize(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminViewAddsMoneyAgencyNameAndRedactedEventsWithOneTenantLookup() {
        login("SA1", UserType.SUPER_ADMIN, null);
        given(bookings.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .willReturn((Page<PlatformBooking>) new PageImpl<>(List.of(booking("B1", "T1", "A1"), booking("B2", "T1", "A1"))));
        given(tenants.findAllById(List.of("T1"))).willReturn(List.of(Tenant.builder().id("T1").agencyName("Acme Travel").build()));

        PagedResponse<PlatformAdminBookingResponse> page = service().adminList(0, 25, PlatformBookingStatus.TICKETED, "T1");

        assertThat(page.getContent()).extracting(PlatformAdminBookingResponse::getAgencyName).containsOnly("Acme Travel");
        PlatformAdminBookingResponse first = page.getContent().get(0);
        assertThat(first.getVoyraNet()).isEqualByComparingTo("145.00");
        assertThat(first.getGross()).isEqualByComparingTo("5000.00");
        assertThat(first.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        verify(tenants).findAllById(List.of("T1"));
    }

    @Test
    void adminViewShowsTheEstimateAndTheStatementFigureSideBySide() {
        login("SA1", UserType.SUPER_ADMIN, null);
        PlatformBooking b = booking("B1", "T1", "A1").toBuilder().voyraEarnedActual(new BigDecimal("270.00"))
                .voyraEarnedSource(com.voyra.crm.enums.VoyraEarnedSource.STATEMENT).build();
        given(tenants.findAllById(List.of("T1"))).willReturn(List.of(Tenant.builder().id("T1").agencyName("Acme Travel").build()));

        PlatformAdminBookingResponse r = service().adminView(b);

        assertThat(r.getVoyraEarned()).isEqualByComparingTo("250.00");
        assertThat(r.getVoyraEarnedActual()).isEqualByComparingTo("270.00");
    }

    @Test
    void adminDetailListsEveryEventWithItsPayload() {
        login("SA1", UserType.SUPER_ADMIN, null);
        given(bookings.findById("B1")).willReturn(Optional.of(booking("B1", "T1", "A1")));
        given(tenants.findAllById(List.of("T1"))).willReturn(List.of(Tenant.builder().id("T1").agencyName("Acme Travel").build()));
        given(events.findByPlatformBookingIdOrderByCreatedAtAsc("B1")).willReturn(List.of(event("VARIANCE")));

        PlatformAdminBookingResponse r = service().adminGet("B1");

        assertThat(r.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("VARIANCE");
            assertThat(e.getPayload()).containsEntry("voyraNet", "145.00");
        });
    }

    @Test
    void routeIsReadFromBothTheTripjackArrayWrapAndTheMockShape() {
        login("O1", UserType.AGENCY_OWNER, "T1");
        Map<String, Object> seg = Map.of("fD", Map.of("aI", Map.of("code", "AI", "name", "Air India"), "fN", "865"),
                "da", Map.of("code", "DEL"), "aa", Map.of("code", "BLR"), "dt", "2026-12-02T09:00", "at", "2026-12-02T11:45");
        PlatformBooking tripjack = booking("B1", "T1", "O1");
        tripjack.setItinerary(Map.of("tripInfos", List.of(Map.of("sI", List.of(seg), "totalPriceList", List.of()))));
        PlatformBooking mock = booking("B2", "T1", "O1");
        mock.setItinerary(Map.of("sI", List.of(seg)));
        PlatformBooking none = booking("B3", "T1", "O1");
        none.setItinerary(Map.of("tripInfos", List.of(Map.of("x", 1))));

        assertThat(service().view(tripjack).getItinerary()).singleElement().satisfies(s -> {
            assertThat(s.getFrom()).isEqualTo("DEL");
            assertThat(s.getTo()).isEqualTo("BLR");
            assertThat(s.getFlightNumber()).isEqualTo("AI-865");
        });
        assertThat(service().view(mock).getItinerary()).hasSize(1);
        assertThat(service().view(none).getItinerary()).isEmpty();
    }

    /** A Starter booking has no client (and may have no booker name): the views map it, never 500 after the money moved. */
    @Test
    void aBookingWithoutAClientOrBookerMapsInEveryView() {
        login("O1", UserType.AGENCY_OWNER, "T1");
        PlatformBooking starter = booking("B1", "T1", null);
        starter.setClientId(null);
        starter.setTenantId("T1");
        given(bookings.findByTenantId(eq("T1"), any(Pageable.class))).willReturn(new PageImpl<>(List.of(starter)));
        given(bookings.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(starter)));

        assertThat(service().view(starter).getClientName()).isNull(); // what confirm maps its answer with
        assertThat(service().list(0, 25).getContent()).singleElement().satisfies(r -> assertThat(r.getBookedByName()).isNull());
        assertThat(service().adminView(starter).getBookedByName()).isNull();
        assertThat(service().adminList(0, 25, null, null).getContent()).hasSize(1);
    }

    @Test
    void aHotelBookingRendersItsStayAndHotelWordingWithoutPanOrSupplierIds() throws Exception {
        login("O1", UserType.AGENCY_OWNER, "T1");
        Map<String, Object> itinerary = new java.util.LinkedHashMap<>();
        itinerary.put("hotel", Map.of("name", "Sea Breeze Resort", "city", "Goa", "address", "Calangute"));
        itinerary.put("stay", Map.of("checkIn", "2026-12-01", "checkOut", "2026-12-03", "nights", 2));
        itinerary.put("rooms", List.of(Map.of("adults", 2, "mealBasis", "Breakfast included"), Map.of("adults", 1, "mealBasis", "Breakfast included")));
        itinerary.put("cancellationPolicy", "Free cancellation until 2026-11-29");
        PlatformBooking b = booking("B1", "T1", "O1").toBuilder().product(PricingProduct.HOTEL).itinerary(itinerary).clientId(null)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(Map.of("type", "ADULT", "title", "Mr", "firstName", "Rahul",
                        "lastName", "Sharma", "roomIndex", 0, "pan", "ABCDE1234F"))))).build();
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(events.findByPlatformBookingIdOrderByCreatedAtAsc("B1")).willReturn(List.of(event("REVIEWED"), event("BOOKING"), event("TICKETED")));

        PlatformBookingResponse r = service().get("B1");
        String json = objectMapper.writeValueAsString(r);

        for (String forbidden : List.of("voyraEarned", "voyraNet", "gross", "RV-SECRET", "TJS-SECRET", "ABCDE1234F", "\"pan\"")) {
            assertThat(json).as(forbidden).doesNotContain(forbidden);
        }
        assertThat(r.getProduct()).isEqualTo(PricingProduct.HOTEL);
        assertThat(r.getItinerary()).isEmpty();
        assertThat(r.getHotel()).isEqualTo(new HotelStaySummary("Sea Breeze Resort", "Goa", java.time.LocalDate.of(2026, 12, 1),
                java.time.LocalDate.of(2026, 12, 3), 2, 2, "Breakfast included"));
        assertThat(r.getPassengers()).singleElement().satisfies(p -> assertThat(p.getName()).isEqualTo("Mr Rahul Sharma"));
        assertThat(r.getTimeline()).extracting(l -> l.getText()).containsExactly("Rate reviewed", "Sent to the hotel", "Booking confirmed");
    }

    @Test
    void aFlightBookingHasNoHotelSummary() {
        assertThat(service().hotel(booking("B1", "T1", "O1"))).isNull();
    }

    /** Readiness: the agency never sees the supplier's own words - a fixed plain sentence per state; the raw text is admin-only. */
    @Test
    void theAgencySeesAFixedSupplierMessageAndOnlyTheAdminSeesTheRawText() throws Exception {
        login("O1", UserType.AGENCY_OWNER, "T1");
        java.util.function.BiFunction<PlatformBookingStatus, String, PlatformBooking> row = (status, raw) ->
                booking("B1", "T1", "O1").toBuilder().status(status).supplierMessage(raw).build();

        assertThat(service().view(row.apply(PlatformBookingStatus.FAILED, "ERR-4021: TJ fare SOLD OUT at GDS")).getSupplierMessage())
                .isEqualTo("Fare no longer available");
        assertThat(service().view(row.apply(PlatformBookingStatus.FAILED, "Duplicate booking - contact ops@tripjack")).getSupplierMessage())
                .isEqualTo("Booking failed at the supplier");
        assertThat(service().view(row.apply(PlatformBookingStatus.UNKNOWN, null)).getSupplierMessage())
                .isEqualTo("We're confirming with the supplier");
        assertThat(service().view(row.apply(PlatformBookingStatus.CANCELLING, "AMENDMENT_ACCEPTED")).getSupplierMessage())
                .isEqualTo("We're confirming with the supplier");
        assertThat(service().view(row.apply(PlatformBookingStatus.TICKETED, "SUCCESS")).getSupplierMessage()).isNull();
        PlatformBooking hotel = row.apply(PlatformBookingStatus.FAILED, "Room not available").toBuilder().product(PricingProduct.HOTEL).build();
        assertThat(service().view(hotel).getSupplierMessage()).isEqualTo("Room no longer available");
        String json = objectMapper.writeValueAsString(service().view(row.apply(PlatformBookingStatus.FAILED, "ERR-4021 raw")));
        assertThat(json).doesNotContain("ERR-4021").doesNotContain("supplierMessageRaw");

        login("SA1", UserType.SUPER_ADMIN, null);
        PlatformAdminBookingResponse admin = service().adminView(row.apply(PlatformBookingStatus.FAILED, "ERR-4021: TJ fare SOLD OUT at GDS"));
        assertThat(admin.getSupplierMessage()).isEqualTo("Fare no longer available");
        assertThat(admin.getSupplierMessageRaw()).isEqualTo("ERR-4021: TJ fare SOLD OUT at GDS");
    }

    // ---- summary (basic reports)

    @Test
    void summaryAddsUpTicketedSalesAndEarningsPerProductInIstDays() {
        login("T1", UserType.AGENCY_OWNER, "T1");
        java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 27);
        given(bookings.summarize(eq("T1"), eq(null), eq(Instant.parse("2026-09-30T18:30:00Z")), eq(Instant.parse("2026-10-27T18:30:00Z")),
                eq(java.util.EnumSet.of(PlatformBookingStatus.REVIEWING, PlatformBookingStatus.ABORTED)))).willReturn(List.of(
                new Object[]{PricingProduct.FLIGHT, PlatformBookingStatus.TICKETED, 3L, new BigDecimal("13500.00"), new BigDecimal("285.00")},
                new Object[]{PricingProduct.FLIGHT, PlatformBookingStatus.CANCELLED, 1L, new BigDecimal("4500.00"), new BigDecimal("95.00")},
                new Object[]{PricingProduct.HOTEL, PlatformBookingStatus.TICKETED, 1L, new BigDecimal("9000"), 0},
                new Object[]{PricingProduct.HOTEL, PlatformBookingStatus.FAILED, 2L, new BigDecimal("0"), new BigDecimal("0")}));
        PlatformBooking upcoming = booking("B1", "T1", "T1");
        given(bookings.findUpcoming(eq("T1"), eq(null), any())).willReturn(List.of(upcoming));
        given(bookings.findNewestInStatuses(eq("T1"), eq(null), eq(java.util.EnumSet.of(PlatformBookingStatus.BOOKING,
                PlatformBookingStatus.UNKNOWN, PlatformBookingStatus.CANCELLING)), any())).willReturn(List.of());

        com.voyra.crm.dto.PlatformBookingSummaryResponse r = service().summary(from, to);

        assertThat(r.getFrom()).isEqualTo(from);
        assertThat(r.getTo()).isEqualTo(to);
        assertThat(r.getBookings()).isEqualTo(7);
        assertThat(r.getTicketed()).isEqualTo(4);
        assertThat(r.getCancelled()).isEqualTo(1);
        assertThat(r.getSales()).isEqualTo(new BigDecimal("22500.00"));
        assertThat(r.getEarned()).isEqualTo(new BigDecimal("285.00"));
        assertThat(r.getByProduct()).extracting(com.voyra.crm.dto.PlatformBookingSummaryResponse.ProductSummary::product)
                .containsExactly(PricingProduct.FLIGHT, PricingProduct.HOTEL);
        assertThat(r.getByProduct().get(0).bookings()).isEqualTo(4);
        assertThat(r.getByProduct().get(1).bookings()).isEqualTo(3);
        assertThat(r.getByProduct().get(1).sales()).isEqualTo(new BigDecimal("9000.00"));
        assertThat(r.getUpcoming()).extracting(PlatformBookingResponse::getId).containsExactly("B1");
        assertThat(r.getAttention()).isEmpty();
    }

    @Test
    void summaryOfAnEmptyRangeStillListsBothProducts() {
        login("T1", UserType.ACCOUNTANT, "T1");
        given(bookings.summarize(any(), any(), any(), any(), any())).willReturn(List.of());
        com.voyra.crm.dto.PlatformBookingSummaryResponse r = service().summary(null, null);
        assertThat(r.getTo()).isEqualTo(com.voyra.crm.util.FinancialYear.today());
        assertThat(r.getFrom()).isEqualTo(com.voyra.crm.util.FinancialYear.today().withDayOfMonth(1));
        assertThat(r.getByProduct()).hasSize(2).allSatisfy(ps -> {
            assertThat(ps.bookings()).isZero();
            assertThat(ps.sales()).isEqualTo(new BigDecimal("0.00"));
        });
        assertThat(r.getSales()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void anAgentsSummaryCountsOnlyTheirOwnBookings() {
        login("AG1", UserType.AGENT, "T1");
        given(bookings.summarize(eq("T1"), eq("AG1"), any(), any(), any())).willReturn(List.of());
        service().summary(null, null);
        verify(bookings).findUpcoming(eq("T1"), eq("AG1"), any());
        verify(bookings).findNewestInStatuses(eq("T1"), eq("AG1"), any(), any());
    }

    @Test
    void summaryRefusesABackwardsOrOverlongRange() {
        login("T1", UserType.AGENCY_OWNER, "T1");
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> service().summary(d, d.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().summary(d, d.plusDays(366))).isInstanceOf(IllegalArgumentException.class);
        given(bookings.summarize(any(), any(), any(), any(), any())).willReturn(List.of());
        assertThat(service().summary(d, d.plusDays(365)).getBookings()).isZero();
    }

    // ---------------------------------------------------------------- coverage: admin list filters, odd rows

    @Test
    void anAgentOpensTheirOwnBooking() {
        login("A1", UserType.AGENT, "T1");
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("B1", "T1", "A1")));

        assertThat(service().get("B1").getId()).isEqualTo("B1");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theAdminListFiltersByStatusAndAgencyNewestFirst() {
        org.mockito.ArgumentCaptor<org.springframework.data.jpa.domain.Specification<PlatformBooking>> spec =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.jpa.domain.Specification.class);
        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        given(bookings.findAll(spec.capture(), pageable.capture())).willAnswer(i -> new PageImpl<>(List.of(), i.getArgument(1), 0));

        service().adminList(null, null, PlatformBookingStatus.FAILED, "T1");
        service().adminList(1, 10, null, " ");
        service().adminList(null, null, null, null);

        assertThat(pageable.getAllValues().get(0).getPageNumber()).isZero();
        assertThat(pageable.getAllValues().get(0).getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageable.getAllValues().get(1).getPageNumber()).isEqualTo(1);
        jakarta.persistence.criteria.Root<PlatformBooking> root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.CriteriaBuilder cb = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        jakarta.persistence.criteria.Path<Object> statusPath = org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.Path<Object> tenantPath = org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        given(root.get("status")).willReturn(statusPath);
        given(root.get("tenantId")).willReturn(tenantPath);

        spec.getAllValues().get(0).toPredicate(root, null, cb);
        spec.getAllValues().get(1).toPredicate(root, null, cb);
        spec.getAllValues().get(2).toPredicate(root, null, cb);

        org.mockito.Mockito.verify(cb).equal(statusPath, PlatformBookingStatus.FAILED);
        org.mockito.Mockito.verify(cb).equal(tenantPath, "T1");
        org.mockito.Mockito.verify(cb, org.mockito.Mockito.times(2)).and(new jakarta.persistence.criteria.Predicate[0]);
    }

    @Test
    void anUnknownAdminBookingIs404() {
        given(bookings.findById("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().adminGet("NOPE")).isInstanceOf(NotFoundException.class).hasMessage("Booking not found");
    }

    @Test
    void anAgentWhoseIdIsTheAgencysIsNamedAsTheAgentNotTheOwner() {
        given(agents.findAllById(List.of("T1"))).willReturn(List.of(Agent.builder().id("T1").name("Agent Same Id").build()));

        PlatformBookingResponse r = service().view(booking("B1", "T1", "T1").toBuilder().clientId(null).build());

        assertThat(r.getBookedByName()).isEqualTo("Agent Same Id");
        org.mockito.Mockito.verifyNoInteractions(tenants);
    }

    @Test
    void earningsWithoutCommissionOrTdsAndOddPassengersAndLegs() {
        PlatformBooking noCommission = booking("B1", "T1", null).toBuilder().clientId(null).agencyCommission(null).build();
        PlatformBooking noTds = noCommission.toBuilder().agencyCommission(new BigDecimal("80.00")).tds(null)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", "not a list"))).itinerary(null).build();
        Map<String, Object> blankTitle = new java.util.HashMap<>(Map.of("type", "CHILD", "title", " ", "firstName", "Pax"));
        PlatformBooking oddLegs = noCommission.toBuilder()
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(blankTitle))))
                .itinerary(Map.of("sI", List.of(Map.of("fD", Map.of("fN", "77"))))).build();

        assertThat(service().view(noCommission).getAgencyEarns()).isNull();
        PlatformBookingResponse r = service().view(noTds);
        assertThat(r.getAgencyEarns()).isEqualByComparingTo("80.00");
        assertThat(r.getPassengers()).isEmpty();
        assertThat(r.getItinerary()).isEmpty();
        PlatformBookingResponse odd = service().view(oddLegs);
        assertThat(odd.getPassengers()).singleElement().satisfies(p -> assertThat(p.getName()).isEqualTo("Pax"));
        assertThat(odd.getItinerary()).singleElement().satisfies(l -> assertThat(l.getFlightNumber()).isEqualTo("77"));
    }

    @Test
    void aHotelStayWithUnreadableDatesHasNone() {
        PlatformBooking b = booking("B1", "T1", "O1").toBuilder().product(PricingProduct.HOTEL)
                .itinerary(Map.of("stay", Map.of("checkIn", "soon", "checkOut", 5))).build();

        HotelStaySummary stay = service().hotel(b);

        assertThat(stay.checkIn()).isNull();
        assertThat(stay.checkOut()).isNull();
        assertThat(stay.nights()).isNull();
        assertThat(stay.rooms()).isNull();
        assertThat(service().hotel(b.toBuilder().itinerary(null).build())).isNull();
    }
}
