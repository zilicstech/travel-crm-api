package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingLogRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** How a logged air ticket names its travellers and flights, and the request checks before the voucher is stored. */
@ExtendWith(MockitoExtension.class)
class BookingLogServiceCoverageTest {

    @Mock private BookingService bookingService;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private FileStorageService fileStorageService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private BookingLogService service;

    private final MockMultipartFile voucher = new MockMultipartFile("file", "t.pdf", "application/pdf", new byte[]{1});
    private static final LocalDate D1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 5);

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static FlightSectorDto sector(String from, String to, LocalDate date) {
        FlightSectorDto s = new FlightSectorDto();
        s.setFrom(from);
        s.setTo(to);
        s.setDate(date);
        return s;
    }

    private static LeadService flightService(FlightTripType trip, List<FlightSectorDto> sectors) {
        return LeadService.builder().id("S1").leadId("L1").clientId("C1").type(ServiceType.FLIGHT)
                .flightTripType(trip).flightSectors(sectors).label("Flight").build();
    }

    private static BookingLogRequest onService() {
        BookingLogRequest r = new BookingLogRequest();
        r.setServiceId("S1");
        r.setReference(" PNR123 ");
        r.setNetCost(new BigDecimal("9000"));
        r.setSellingPrice(new BigDecimal("10000"));
        return r;
    }

    private static LeadMember member(String id, String memberId, LeadMemberStatus status) {
        return LeadMember.builder().id(id).memberId(memberId).memberName("Pax " + id).status(status)
                .fareClass(PaxType.ADULT).build();
    }

    private BookingDocument stubBooking(String documentDetailsKey) {
        when(fileStorageService.store(eq("T1"), eq("booking-documents"), anyString(), eq(voucher))).thenReturn("key-1");
        when(bookingService.logBooking(any(), anyString(), eq("key-1"), eq("t.pdf"), eq("application/pdf"),
                eq(BookingDocumentType.E_TICKET), eq("PNR123"), any())).thenReturn(BookingResponse.builder().id("B1").build());
        Map<String, String> details = new HashMap<>();
        if (documentDetailsKey != null) details.put(documentDetailsKey, "x");
        BookingDocument doc = BookingDocument.builder().id("D").details(details.isEmpty() ? null : details).build();
        when(bookingDocumentRepository.findById(anyString())).thenReturn(Optional.of(doc));
        return doc;
    }

    @Test
    void aRoundTripTicketNamesTheTravellersWithAMemberAndOnlyCompleteFlights() {
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flightService(FlightTripType.ROUND_TRIP, List.of(
                sector(null, "DXB", D1), sector("BOM", null, D1), sector("BOM", "DXB", null),
                sector("BOM", "DXB", D1), sector("DXB", "BOM", D2)))));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                member("LM1", "M1", LeadMemberStatus.CONFIRMED),
                member("LM2", null, LeadMemberStatus.TENTATIVE),
                member("LM3", "M3", LeadMemberStatus.DROPPED)));
        BookingDocument doc = stubBooking(null);

        BookingResponse out = service.log(onService(), voucher);

        assertThat(out.getId()).isEqualTo("B1");
        assertThat(doc.getTravellers()).extracting(VoucherTraveller::getMemberId).containsExactly("M1");
        assertThat(doc.getTravellers().get(0).getName()).isEqualTo("Pax LM1");
        assertThat(doc.getTravellers().get(0).getPaxType()).isEqualTo(PaxType.ADULT);
        assertThat(doc.getLegs()).extracting(VoucherLeg::getFrom).containsExactly("BOM", "DXB");
        assertThat(doc.getLegs()).extracting(VoucherLeg::getTravelDate).containsExactly(D1, D2);
        assertThat(doc.getDetails()).containsEntry("flightFrom", "BOM").containsEntry("flightTo", "DXB")
                .containsEntry("returnDate", D2.toString());
        verify(bookingDocumentRepository).save(doc);
        verify(fileStorageService, never()).delete(anyString());
    }

    @Test
    void aTicketWithNoTravellersUsesTheServicesFirstFlight() {
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(
                flightService(FlightTripType.ONE_WAY, List.of(sector("DEL", "GOI", D1)))));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of());
        BookingDocument doc = stubBooking(null);

        service.log(onService(), voucher);

        ArgumentCaptor<BookingCreateRequest> create = ArgumentCaptor.forClass(BookingCreateRequest.class);
        verify(bookingService).logBooking(create.capture(), anyString(), any(), any(), any(), any(), any(), any());
        assertThat(create.getValue().getPassengers()).isNull();
        assertThat(doc.getTravellers()).isNull();
        assertThat(doc.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getFrom()).isEqualTo("DEL");
            assertThat(l.getTo()).isEqualTo("GOI");
            assertThat(l.getTravelDate()).isEqualTo(D1);
        });
        assertThat(doc.getDetails()).containsEntry("journeyDate", D1.toString());
    }

    @Test
    void anIncompleteFirstFlightOrNoSectorsLeavesTheTicketWithoutFlights() {
        for (FlightSectorDto incomplete : List.of(sector("DEL", null, D1), sector("DEL", "GOI", null))) {
            org.mockito.Mockito.reset(leadServiceRepository, leadMemberRepository, bookingDocumentRepository, fileStorageService, bookingService);
            when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(
                    flightService(FlightTripType.ONE_WAY, new ArrayList<>(List.of(incomplete)))));
            when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of());
            BookingDocument doc = stubBooking("note");

            service.log(onService(), voucher);

            assertThat(doc.getLegs()).isNull();
            assertThat(doc.getDetails()).containsOnlyKeys("note");
        }

        org.mockito.Mockito.reset(leadServiceRepository, leadMemberRepository, bookingDocumentRepository, fileStorageService, bookingService);
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flightService(FlightTripType.ONE_WAY, null)));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(member("LM1", "M1", LeadMemberStatus.CONFIRMED)));
        BookingDocument doc = stubBooking(null);
        service.log(onService(), voucher);
        assertThat(doc.getLegs()).isNull();
        assertThat(doc.getDetails()).isNull();
        assertThat(doc.getTravellers()).extracting(VoucherTraveller::getMemberId).containsExactly("M1");
    }

    @Test
    void flightsOutOfOrderAreDroppedSoTheVouchersEditAsksForThem() {
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flightService(FlightTripType.ROUND_TRIP, List.of(
                sector("BOM", "DXB", D2), sector("DXB", "BOM", D1)))));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(member("LM1", "M1", LeadMemberStatus.CONFIRMED)));
        BookingDocument doc = stubBooking(null);

        service.log(onService(), voucher);

        assertThat(doc.getLegs()).isNull();
        assertThat(doc.getTravellers()).hasSize(1);
    }

    @Test
    void anAdHocFlightWithNoDocumentRowNamesNothing() {
        BookingLogRequest r = new BookingLogRequest();
        r.setClientId("C1");
        r.setType(BookingType.FLIGHT);
        r.setDestination("Dubai");
        r.setReference("PNR123");
        r.setVendorId("V1");
        r.setNetCost(new BigDecimal("1000"));
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupInput(new BigDecimal("10"));
        r.setMarkupCurrency("USD");
        r.setMarkupRate(new BigDecimal("84"));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Emirates").build()));
        when(fileStorageService.store(any(), any(), any(), any())).thenReturn("key-1");
        when(bookingService.logBooking(any(), anyString(), eq("key-1"), any(), any(), eq(BookingDocumentType.E_TICKET),
                eq("PNR123"), any())).thenReturn(BookingResponse.builder().id("B2").build());
        when(bookingDocumentRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThat(service.log(r, voucher).getId()).isEqualTo("B2");

        assertThat(r.getMarkupValue()).isEqualByComparingTo("840.00");
        ArgumentCaptor<BookingCreateRequest> create = ArgumentCaptor.forClass(BookingCreateRequest.class);
        verify(bookingService).logBooking(create.capture(), anyString(), any(), any(), any(), any(), any(), any());
        assertThat(create.getValue().getSupplier()).isEqualTo("Emirates");
        assertThat(create.getValue().getSellingPrice()).isEqualByComparingTo("1840");
        verify(bookingDocumentRepository, never()).save(any());
    }

    @Test
    void refusesANullVoucherAnUnknownServiceAndAnUnknownVendorBeforeStoringAnything() {
        assertThatThrownBy(() -> service.log(onService(), null)).hasMessage("Voucher file is required");

        when(leadServiceRepository.findById("S1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.log(onService(), voucher))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service not found: S1");

        BookingLogRequest blankService = onService();
        blankService.setServiceId(" ");
        blankService.setVendorId("V9");
        when(vendorRepository.findById("V9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.log(blankService, voucher))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Vendor not found: V9");
        verifyNoInteractions(fileStorageService, bookingService);
    }

    @Test
    void aBlankVendorAndNoCostLeaveTheRequestAsTyped() {
        BookingLogRequest r = new BookingLogRequest();
        r.setClientId("C1");
        r.setType(BookingType.HOTEL);
        r.setDestination("Goa");
        r.setReference("HC-9");
        r.setVendorId(" ");
        r.setSellingPrice(new BigDecimal("500"));
        when(fileStorageService.store(any(), any(), any(), any())).thenReturn("key-3");
        when(bookingService.logBooking(any(), anyString(), eq("key-3"), any(), any(), eq(BookingDocumentType.HOTEL_VOUCHER),
                eq("HC-9"), any())).thenReturn(BookingResponse.builder().id("B3").build());

        service.log(r, voucher);

        assertThat(r.getNetCost()).isNull();
        assertThat(r.getMarkupValue()).isNull();
        verifyNoInteractions(vendorRepository);
        verify(bookingDocumentRepository, never()).findById(anyString());
    }
}
