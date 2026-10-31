package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.TicketDownloadResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingTicket;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformBookingTicketRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.AgencyLogoLoader;
import com.voyra.crm.service.AuthorResolver;
import com.voyra.crm.service.booking.ticket.FareLine;
import com.voyra.crm.support.LogCapture;
import com.voyra.crm.util.ETicketPdfRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlatformBookingTicketServiceTest {

    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";
    private static final Instant TICKETED_AT = Instant.parse("2026-10-25T05:02:00Z");

    @Mock private PlatformBookingRepository bookings;
    @Mock private PlatformBookingEventRepository events;
    @Mock private TenantRepository tenants;
    @Mock private AgentRepository agents;
    @Mock private ClientRepository clients;
    @Mock private PlatformBookingTicketRepository tickets;
    @Mock private AuthorResolver authorResolver;
    @Mock private AgencyLogoLoader logoLoader;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaxCodec paxCodec = new PaxCodec(objectMapper, KEY);
    private final List<PlatformBookingTicket> saved = new ArrayList<>();
    private PlatformBookingTicketService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBookingTicketService(new PlatformBookingQueryService(bookings, events, tenants, agents, clients, paxCodec, objectMapper),
                bookings, tickets, tenants, authorResolver, logoLoader, paxCodec, objectMapper);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String userId, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@x.test", type, "T1"), null));
    }

    private static Map<String, Object> pax(String title, String first, String last, String passport) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "ADULT");
        m.put("title", title);
        m.put("firstName", first);
        m.put("lastName", last);
        m.put("passportNo", passport);
        m.put("dob", "1990-04-12");
        m.put("nationality", "IN");
        m.put("passportExpiry", "2031-01-31");
        return m;
    }

    private static Map<String, Object> seg(String from, String to, boolean back) {
        return Map.of("fD", Map.of("aI", Map.of("code", "6E", "name", "IndiGo"), "fN", "201"), "da", Map.of("code", from),
                "aa", Map.of("code", to), "dt", "2026-12-01T06:00", "at", "2026-12-01T08:10", "isRs", back);
    }

    /** A one-way, one-passenger mock-shape flight: gross 5000, base 4250, no tax breakdown. */
    private PlatformBooking booking(String bookedBy) {
        return PlatformBooking.builder().id("B1").tenantId("T1").product(PricingProduct.FLIGHT)
                .status(PlatformBookingStatus.TICKETED).environment(SupplierEnvironment.UAT)
                .pnr("ABC123").ticketNumbers(List.of("098-1"))
                .gross(new BigDecimal("5000.00")).base(new BigDecimal("4250.00")).displayed(new BigDecimal("4500.00"))
                .agencyCommission(new BigDecimal("100.00")).voyraEarned(new BigDecimal("250.00")).paxCount(1)
                .paxEnc(paxCodec.encrypt(Map.of("passengers", List.of(pax("Mr", "Rahul", "Sharma", "N1234567")),
                        "contactEmail", "c@x.test", "contactPhone", "9876543210")))
                .itinerary(new HashMap<>(Map.of("sI", List.of(seg("DEL", "BOM", false)))))
                .bookedBy(bookedBy).createdAt(Instant.parse("2026-10-25T05:00:00Z")).ticketedAt(TICKETED_AT).build();
    }

    /** Wires the repositories for one visible booking whose downloads are kept in {@link #saved}. */
    private void stored(PlatformBooking b) {
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(b));
        given(tickets.maxSeq("B1")).willAnswer(i -> saved.size());
        given(tickets.save(any())).willAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    private void owner() {
        login("T1", UserType.AGENCY_OWNER);
        given(authorResolver.resolveCurrentAuthor()).willReturn(new AuthorResolver.AuthorInfo("T1", "Dana Owner"));
    }

    private void openable(PlatformBookingTicket t) {
        given(tickets.findByIdAndPlatformBookingIdAndTenantId(t.getId(), "B1", "T1")).willReturn(Optional.of(t));
        given(tenants.findById("T1")).willReturn(Optional.of(Tenant.builder().id("T1").agencyName("Sky Travels")
                .contactEmail("a@sky.in").contactPhone("999").address("1 Main Rd").build()));
    }

    /** What the renderer was handed, with the real PDF still rendered. */
    private static ETicketPdfRenderer.ETicketData rendered(Supplier<PlatformBookingTicketService.TicketPdf> call) {
        try (MockedStatic<ETicketPdfRenderer> renderer = mockStatic(ETicketPdfRenderer.class, CALLS_REAL_METHODS)) {
            PlatformBookingTicketService.TicketPdf pdf = call.get();
            assertThat(new String(pdf.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
            ArgumentCaptor<ETicketPdfRenderer.ETicketData> data = ArgumentCaptor.forClass(ETicketPdfRenderer.ETicketData.class);
            renderer.verify(() -> ETicketPdfRenderer.render(data.capture()));
            return data.getValue();
        }
    }

    @Test
    void createsFirstAndSecondDownload() {
        owner();
        PlatformBooking b = booking("T1");
        stored(b);

        TicketDownloadResponse first = service.create("B1", null);
        TicketDownloadResponse second = service.create("B1", new BigDecimal("500.5"));

        assertThat(first.getSeq()).isEqualTo(1);
        assertThat(first.getMarkup()).isEqualByComparingTo("0");
        assertThat(first.getTicketPrice()).isEqualByComparingTo("5000.00");
        assertThat(first.getCreatedByName()).isEqualTo("Dana Owner");
        assertThat(second.getSeq()).isEqualTo(2);
        assertThat(second.getTicketPrice()).isEqualByComparingTo("5500.50");
        PlatformBookingTicket row = saved.get(1);
        assertThat(row.getTenantId()).isEqualTo("T1");
        assertThat(row.getPlatformBookingId()).isEqualTo("B1");
        assertThat(row.getCreatedBy()).isEqualTo("T1");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getMarkup()).isEqualTo(new BigDecimal("500.50"));
        assertThat(row.getFareLines()).containsExactly(Map.of("label", "Base fare", "amount", "4250.00"),
                Map.of("label", "Taxes, fees and charges", "amount", "1250.50"));
    }

    @Test
    void refusesNotTicketed() {
        login("T1", UserType.AGENCY_OWNER);
        PlatformBooking b = booking("T1");
        b.setStatus(PlatformBookingStatus.CANCELLED);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(b));

        assertThatThrownBy(() -> service.create("B1", null)).isInstanceOfSatisfying(BookingConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_AVAILABLE"));
        verify(tickets, never()).save(any());
    }

    @Test
    void refusesHotel() {
        login("T1", UserType.AGENCY_OWNER);
        PlatformBooking b = booking("T1");
        b.setProduct(PricingProduct.HOTEL);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(b));

        assertThatThrownBy(() -> service.create("B1", null)).isInstanceOfSatisfying(BookingConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_AVAILABLE"));
        verify(tickets, never()).save(any());
    }

    @Test
    void refusesPurged() {
        login("T1", UserType.AGENCY_OWNER);
        PlatformBooking b = booking("T1");
        b.setPaxEnc(null);
        b.setPaxPurgedAt(Instant.parse("2027-03-01T00:00:00Z"));
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(b));

        assertThatThrownBy(() -> service.create("B1", null)).isInstanceOfSatisfying(BookingConflictException.class, e -> {
            assertThat(e.getCode()).isEqualTo("PASSENGERS_PURGED");
            assertThat(e.getMessage()).isEqualTo(ETicketPdfRenderer.PURGED_TEXT);
        });
        verify(tickets, never()).save(any());
    }

    /** Negative and three-decimal markups are refused by validation (controller test); above the published fare here. */
    @Test
    void markupRules() {
        owner();
        stored(booking("T1"));

        assertThatThrownBy(() -> service.create("B1", new BigDecimal("5000.01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Markup can be at most the published fare");
        assertThat(saved).isEmpty();
        assertThat(service.create("B1", new BigDecimal("5000")).getTicketPrice()).isEqualByComparingTo("10000.00");
    }

    @Test
    void refusesAFlightWithNoFareBreakdown() {
        login("T1", UserType.AGENCY_OWNER);
        PlatformBooking b = booking("T1");
        b.setGross(null);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(b));
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(b));

        assertThatThrownBy(() -> service.create("B1", new BigDecimal("10"))).isInstanceOfSatisfying(BookingConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_AVAILABLE"));
    }

    /** The named taxes and the total are frozen at download: a reopen after the itinerary changed prints them unchanged. */
    @Test
    void reopenPrintsFrozenLines() {
        owner();
        PlatformBooking b = booking("T1");
        b.getItinerary().put("totalPriceList", List.of(Map.of("fd", Map.of("ADULT", Map.of("afC", Map.of("TAF", Map.of("YQ", 500, "K3", 200)))))));
        b.setPricingSnapshot(Map.of("paxMix", Map.of("adults", 1, "children", 0, "infants", 0)));
        stored(b);
        service.create("B1", BigDecimal.ZERO); // no markup, so the supplier's named taxes are printed
        List<FareLine> frozen = List.of(new FareLine("Base fare", new BigDecimal("4250.00")), new FareLine("K3", new BigDecimal("200.00")),
                new FareLine("YQ", new BigDecimal("500.00")), new FareLine("Other charges", new BigDecimal("50.00")));

        b.setItinerary(Map.of("sI", List.of(seg("DEL", "BOM", false))));
        b.setPricingSnapshot(null);
        PlatformBookingTicket t = saved.get(0);
        openable(t);
        ETicketPdfRenderer.ETicketData d = rendered(() -> service.pdf("B1", t.getId()));

        assertThat(d.fare()).isEqualTo(frozen);
        assertThat(d.total()).isEqualByComparingTo("5000.00");
    }

    @Test
    void pdfPrintsTheAgencyTheBookingAndEachPassenger() {
        owner();
        stored(booking("T1"));
        service.create("B1", null);
        PlatformBookingTicket t = saved.get(0);
        openable(t);
        byte[] logo = {1, 2, 3};
        given(logoLoader.load(any())).willReturn(logo);

        PlatformBookingTicketService.TicketPdf[] pdf = new PlatformBookingTicketService.TicketPdf[1];
        ETicketPdfRenderer.ETicketData d = rendered(() -> pdf[0] = service.pdf("B1", t.getId()));

        assertThat(pdf[0].filename()).isEqualTo("e-ticket-ABC123-1.pdf");
        assertThat(d.agencyName()).isEqualTo("Sky Travels");
        assertThat(d.email()).isEqualTo("a@sky.in");
        assertThat(d.phone()).isEqualTo("999");
        assertThat(d.address()).isEqualTo("1 Main Rd");
        assertThat(d.agencyLogo()).isSameAs(logo);
        assertThat(d.bookingId()).isEqualTo("B1");
        assertThat(d.issuedAt()).isEqualTo(TICKETED_AT);
        assertThat(d.statusText()).isEqualTo("Confirmed");
        assertThat(d.watermarkCancelled()).isFalse();
        assertThat(d.watermarkTest()).isTrue();
        assertThat(d.facts().directions()).hasSize(1);
        assertThat(d.passengers()).containsExactly(new ETicketPdfRenderer.PaxRow("Mr Rahul Sharma", "ADULT", LocalDate.of(1990, 4, 12),
                "N1234567", "IN", LocalDate.of(2031, 1, 31), "DEL-BOM", "ABC123", "098-1"));
    }

    /** A round trip with two passengers: each row takes its own passenger's and sector's ticket and PNR; the flat list is never used. */
    @Test
    void roundTripRowsUseTheirOwnPassengerAndSector() {
        owner();
        PlatformBooking b = booking("T1");
        b.setItinerary(Map.of("sI", List.of(seg("DEL", "BOM", false), seg("BOM", "DEL", true))));
        b.setPaxEnc(paxCodec.encrypt(Map.of("passengers", List.of(pax("Mr", "Rahul", "Sharma", "N1"), pax(null, "Asha", "Rao", null)))));
        b.setPnr("P-1 / P2");
        b.setTicketNumbers(List.of("FLAT-1", "FLAT-2"));
        b.setTicketDetails(List.of(line(0, "DEL-BOM", "P-1", "T01"), line(0, "BOM-DEL", "P2", "T02"),
                line(1, "DEL-BOM", "P-1", "T11"), line(1, "BOM-DEL", null, null)));
        stored(b);
        service.create("B1", null);
        PlatformBookingTicket t = saved.get(0);
        openable(t);

        PlatformBookingTicketService.TicketPdf[] pdf = new PlatformBookingTicketService.TicketPdf[1];
        ETicketPdfRenderer.ETicketData d = rendered(() -> pdf[0] = service.pdf("B1", t.getId()));

        assertThat(pdf[0].filename()).isEqualTo("e-ticket-P_1-1.pdf");
        assertThat(d.passengers()).extracting(ETicketPdfRenderer.PaxRow::name, ETicketPdfRenderer.PaxRow::sector,
                        ETicketPdfRenderer.PaxRow::pnr, ETicketPdfRenderer.PaxRow::ticketNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Mr Rahul Sharma", "DEL-BOM", "P-1", "T01"),
                        org.assertj.core.groups.Tuple.tuple("Mr Rahul Sharma", "BOM-DEL", "P2", "T02"),
                        org.assertj.core.groups.Tuple.tuple("Asha Rao", "DEL-BOM", "P-1", "T11"),
                        org.assertj.core.groups.Tuple.tuple("Asha Rao", "BOM-DEL", "P-1 / P2", "-"));
    }

    /** An admin-resolved round trip has no ticket_details: every row prints the booking's PNR, never a positional ticket number. */
    @Test
    void adminResolvedRoundTripPrintsTheBookingPnrOnBothDirections() {
        owner();
        PlatformBooking b = booking("T1");
        b.setItinerary(Map.of("sI", List.of(seg("DEL", "BOM", false), seg("BOM", "DEL", true))));
        b.setPnr("XYZ999");
        b.setTicketNumbers(List.of("FLAT-1"));
        stored(b);
        service.create("B1", null);
        PlatformBookingTicket t = saved.get(0);
        openable(t);

        ETicketPdfRenderer.ETicketData d = rendered(() -> service.pdf("B1", t.getId()));

        assertThat(d.passengers()).extracting(ETicketPdfRenderer.PaxRow::sector, ETicketPdfRenderer.PaxRow::pnr,
                        ETicketPdfRenderer.PaxRow::ticketNumber)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DEL-BOM", "XYZ999", "-"),
                        org.assertj.core.groups.Tuple.tuple("BOM-DEL", "XYZ999", "-"));
    }

    @Test
    void anUnknownBookingIs404() {
        login("T1", UserType.AGENCY_OWNER);
        given(bookings.findByIdAndTenantIdForUpdate("NOPE", "T1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("NOPE", null)).isInstanceOf(NotFoundException.class).hasMessage("Booking not found");
        verifyNoInteractions(tickets);
    }

    @Test
    void anotherAgencysBookingIsNeverLocked() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("T2", "o@t2.test", UserType.AGENCY_OWNER, "T2"), null));

        assertThatThrownBy(() -> service.create("B1", null)).isInstanceOf(NotFoundException.class).hasMessage("Booking not found");
        verify(bookings).findByIdAndTenantIdForUpdate("B1", "T2");
        verify(bookings, never()).findByIdForUpdate(any());
        verifyNoInteractions(tickets);
    }

    private static Map<String, Object> line(int pax, String sector, String pnr, String ticket) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paxIndex", pax);
        m.put("sector", sector);
        m.put("pnr", pnr);
        m.put("ticketNumber", ticket);
        return m;
    }

    @Test
    void aCancellingLiveBookingWithoutPnrOrTicketNumbersStillPrints() {
        owner();
        PlatformBooking b = booking("T1");
        stored(b);
        service.create("B1", null);
        b.setStatus(PlatformBookingStatus.CANCELLING);
        b.setEnvironment(SupplierEnvironment.PROD);
        b.setPnr(null);
        b.setTicketNumbers(null);
        b.setTicketedAt(null);
        PlatformBookingTicket t = saved.get(0);
        openable(t);

        PlatformBookingTicketService.TicketPdf[] pdf = new PlatformBookingTicketService.TicketPdf[1];
        ETicketPdfRenderer.ETicketData d = rendered(() -> pdf[0] = service.pdf("B1", t.getId()));

        assertThat(pdf[0].filename()).isEqualTo("e-ticket-B1-1.pdf");
        assertThat(d.statusText()).isEqualTo("Cancellation in progress");
        assertThat(d.watermarkCancelled()).isTrue();
        assertThat(d.watermarkTest()).isFalse();
        assertThat(d.issuedAt()).isEqualTo(b.getCreatedAt());
        assertThat(d.passengers()).singleElement().satisfies(p -> {
            assertThat(p.pnr()).isNull();
            assertThat(p.ticketNumber()).isEqualTo("-");
        });
    }

    @Test
    void purgedReopenHasNoPassengers() {
        owner();
        PlatformBooking b = booking("T1");
        stored(b);
        service.create("B1", null);
        b.setPaxEnc(null);
        b.setPaxPurgedAt(Instant.parse("2027-03-01T00:00:00Z"));
        b.setStatus(PlatformBookingStatus.CANCELLED);
        PlatformBookingTicket t = saved.get(0);
        openable(t);

        ETicketPdfRenderer.ETicketData d = rendered(() -> service.pdf("B1", t.getId()));

        assertThat(d.passengers()).isNull();
        assertThat(d.statusText()).isEqualTo("Cancelled");
        assertThat(d.watermarkCancelled()).isTrue();
    }

    @Test
    void listIsNewestFirstAndCarriesNoFareBreakdown() {
        login("ACC1", UserType.ACCOUNTANT);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("A1")));
        given(tickets.findByPlatformBookingIdAndTenantIdOrderBySeqDesc("B1", "T1")).willReturn(List.of(
                PlatformBookingTicket.builder().id("K2").seq(2).markup(new BigDecimal("500.00")).total(new BigDecimal("5500.00"))
                        .createdByName("Liam").createdAt(Instant.parse("2026-10-31T06:00:00Z")).build(),
                PlatformBookingTicket.builder().id("K1").seq(1).markup(BigDecimal.ZERO).total(new BigDecimal("5000.00"))
                        .createdByName("Liam").createdAt(Instant.parse("2026-10-31T05:00:00Z")).build()));

        List<TicketDownloadResponse> list = service.list("B1");

        assertThat(list).extracting(TicketDownloadResponse::getId).containsExactly("K2", "K1");
        assertThat(list.get(0).getTicketPrice()).isEqualByComparingTo("5500.00");
        assertThat(list.get(0).getMarkup()).isEqualByComparingTo("500.00");
        assertThat(list.get(0).getCreatedByName()).isEqualTo("Liam");
        assertThat(list.get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-10-31T06:00:00Z"));
    }

    @Test
    void agentCannotOpenColleaguesTicket() {
        login("A2", UserType.AGENT);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("A1")));

        assertThatThrownBy(() -> service.pdf("B1", "K1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list("B1")).isInstanceOf(NotFoundException.class);
        // lock-first: the colleague's row is locked until the refused transaction rolls back, and no ticket is touched
        given(bookings.findByIdAndTenantIdForUpdate("B1", "T1")).willReturn(Optional.of(booking("A1")));
        assertThatThrownBy(() -> service.create("B1", null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(tickets, authorResolver);
    }

    @Test
    void aTicketOfAnotherBookingIs404() {
        login("A1", UserType.AGENT);
        given(bookings.findByIdAndTenantId("B1", "T1")).willReturn(Optional.of(booking("A1")));
        given(tickets.findByIdAndPlatformBookingIdAndTenantId("K-OF-B2", "B1", "T1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pdf("B1", "K-OF-B2")).isInstanceOf(NotFoundException.class).hasMessage("Ticket not found");
    }

    @Test
    void logsNoPassengerData() {
        owner();
        stored(booking("T1"));
        try (LogCapture logs = new LogCapture(PlatformBookingTicketService.class)) {
            service.create("B1", new BigDecimal("250"));
            PlatformBookingTicket t = saved.get(0);
            openable(t);
            service.pdf("B1", t.getId());

            assertThat(logs.lines()).hasSize(2).allSatisfy(l -> assertThat(l).startsWith("INFO").contains("B1")
                    .doesNotContain("Rahul", "Sharma", "N1234567", "c@x.test", "9876543210"));
        }
    }
}
