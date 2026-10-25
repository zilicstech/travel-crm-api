package com.voyra.crm.service.booking;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.MarkupDefault;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MarkupDefaultRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.ExchangeRateProvider;
import com.voyra.crm.service.SupplierInvoiceService;
import com.voyra.crm.service.SupplierPaymentService;
import com.voyra.crm.util.FxMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformBookingMirrorTenantServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private BookingCancellationService cancellationService;
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @Mock private SupplierPaymentService supplierPaymentService;
    @Mock private VendorRepository vendorRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private MarkupDefaultRepository markupDefaultRepository;
    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);

    @InjectMocks private PlatformBookingMirrorTenantService service;

    private final AtomicReference<BookingDocument> ticket = new AtomicReference<>();
    private final Booking booking = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED).build();

    @BeforeEach
    void setUp() {
        when(vendorRepository.findByNameIgnoreCase("Voyra Platform"))
                .thenReturn(Optional.of(Vendor.builder().id("V-VOYRA").name("Voyra Platform").isActive(true).build()));
        when(exchangeRateProvider.resolve(eq("INR"), eq("INR"), any()))
                .thenReturn(new ResolvedExchangeRate(BigDecimal.ONE, FxRateSource.INR_IDENTITY, LocalDate.now()));
        when(markupDefaultRepository.findById(ServiceType.FLIGHT)).thenReturn(Optional.of(MarkupDefault.builder()
                .serviceType(ServiceType.FLIGHT).mode(MarkupMode.FLAT).value(new BigDecimal("300.00")).build()));
        when(bookingService.logBooking(any(), anyString(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            ticket.set(BookingDocument.builder().id(i.getArgument(1)).bookingId("B1").supplierInvoiceId("SI1").build());
            return BookingResponse.builder().id("B1").build();
        });
        when(bookingDocumentRepository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(ticket.get()));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingRepository.findByPlatformBookingId(anyString())).thenReturn(Optional.empty());
        when(supplierInvoiceService.approveForBooking(eq("SI1"), anyString())).thenReturn(SupplierInvoiceResponse.builder()
                .id("SI1").status(SupplierInvoiceStatus.APPROVED).balanceDue(new BigDecimal("5400.00")).build());
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(Agent.builder().id("A1").build()));
    }

    private static PlatformBooking platformBooking() {
        return PlatformBooking.builder().id("PB1").tenantId("T1").clientId("C1").bookedBy("T1").issuedByAgentId("A1")
                .status(PlatformBookingStatus.TICKETED).pnr("ABC123").ticketNumbers(List.of("0981", "0982"))
                .displayed(new BigDecimal("5400.00")).gross(new BigDecimal("5500.00")).base(new BigDecimal("4500.00"))
                .build();
    }

    private static List<ItinerarySegment> roundTrip() {
        return List.of(new ItinerarySegment("DEL", "BOM", "2026-12-02T09:00", "2026-12-02T11:10", "IndiGo", "6E-201"),
                new ItinerarySegment("BOM", "DEL", "2026-12-06T18:00", "2026-12-06T20:05", "IndiGo", "6E-202"));
    }

    private static List<PaxInput> pax() {
        return List.of(PaxInput.builder().type("ADULT").title("Mr").firstName("Rahul").lastName("Sharma").memberId("M1").build(),
                PaxInput.builder().type("CHILD").firstName("Anya").lastName("Sharma").dob(LocalDate.of(2020, 1, 5)).build());
    }

    @Test
    void mirrorIsAConfirmedVoyraTicketPaidFromTheVoyraWallet() {
        String id = service.create(platformBooking(), roundTrip(), pax());

        assertThat(id).isEqualTo("B1");
        ArgumentCaptor<BookingCreateRequest> request = ArgumentCaptor.forClass(BookingCreateRequest.class);
        ArgumentCaptor<FxMath.Pricing> pricing = ArgumentCaptor.forClass(FxMath.Pricing.class);
        verify(bookingService).logBooking(request.capture(), anyString(), any(), any(), any(),
                eq(BookingDocumentType.E_TICKET), eq("ABC123"), pricing.capture());
        BookingCreateRequest r = request.getValue();
        assertThat(r.getType()).isEqualTo(BookingType.FLIGHT);
        assertThat(r.getClientId()).isEqualTo("C1");
        assertThat(r.getAgentId()).isEqualTo("A1"); // the owner booked; the issuing agent owns the CRM booking
        assertThat(r.getVendorId()).isEqualTo("V-VOYRA");
        assertThat(r.getPnr()).isEqualTo("ABC123");
        assertThat(r.getTicketNo()).isEqualTo("0981, 0982");
        assertThat(r.getFlightFrom()).isEqualTo("DEL");
        assertThat(r.getDestination()).isEqualTo("BOM");
        assertThat(r.getJourneyDate()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(r.getReturnDate()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(r.getNetCost()).isEqualByComparingTo("5400.00");
        assertThat(r.getMarkupMode()).isEqualTo(MarkupMode.FLAT); // the agency's default flight markup
        assertThat(r.getMarkupValue()).isEqualByComparingTo("300.00");
        assertThat(r.getOverrideCreditLimit()).isTrue(); // the ticket is already bought
        assertThat(r.getPassengers()).hasSize(2);
        assertThat(r.getPassengers().get(0).getSectors()).hasSize(2);
        assertThat(pricing.getValue().purchase().amount()).isEqualByComparingTo("5400.00");

        BookingDocument doc = ticket.get();
        assertThat(doc.getPurchaseCurrency()).isEqualTo("INR");
        assertThat(doc.getPurchasePrice()).isEqualByComparingTo("5400.00");
        assertThat(doc.getCharges()).containsEntry("basic", new BigDecimal("4400.00")).containsEntry("otherTax", new BigDecimal("1000.00"));
        assertThat(doc.getLegs()).hasSize(2);
        assertThat(doc.getTravellers()).extracting(t -> t.getName() + "/" + t.getPaxType() + "/" + t.getMemberId())
                .containsExactly("Mr Rahul Sharma/ADULT/M1", "Anya Sharma/CHILD/null");
        assertThat(booking.getPlatformBookingId()).isEqualTo("PB1");

        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getPaymentMode()).isEqualTo(PaymentMode.VOYRA_WALLET);
        assertThat(pay.getValue().getSupplierInvoiceId()).isEqualTo("SI1");
        assertThat(pay.getValue().getAmount()).isEqualByComparingTo("5400.00");
    }

    @Test
    void anExistingMirrorIsReturnedNotWrittenTwice() {
        when(bookingRepository.findByPlatformBookingId("PB1")).thenReturn(Optional.of(booking));

        assertThat(service.create(platformBooking(), roundTrip(), pax())).isEqualTo("B1");

        verify(bookingService, never()).logBooking(any(), anyString(), any(), any(), any(), any(), any(), any());
        verify(supplierPaymentService, never()).pay(any());
    }

    @Test
    void withNoAgentToOwnItTheMirrorFails() {
        PlatformBooking pb = platformBooking();
        pb.setIssuedByAgentId(null);

        assertThatThrownBy(() -> service.create(pb, roundTrip(), pax()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("agent");
        verify(supplierPaymentService, never()).pay(any());
    }

    @Test
    void cancelCallsTheExistingCancelAndRecordsTheRefundAgainstTheVoyraWallet() {
        PlatformBooking pb = platformBooking();
        pb.setStatus(PlatformBookingStatus.CANCELLED);
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        ArgumentCaptor<BookingCancelRequest> request = ArgumentCaptor.forClass(BookingCancelRequest.class);
        verify(cancellationService).cancel(eq("B1"), request.capture());
        assertThat(request.getValue().getType()).isEqualTo(CancellationType.CANCELLATION);
        assertThat(request.getValue().getAirlineCharge()).isEqualByComparingTo("0");
        assertThat(request.getValue().getSupplierCharge()).isEqualByComparingTo("1400.00"); // displayed - refund
        assertThat(request.getValue().getAgencyFee()).isEqualByComparingTo("0");
        verify(cancellationService).recordVoyraWalletRefund("B1", new BigDecimal("4000.00"));
    }

    @Test
    void aMirrorAlreadyCancelledIsLeftAlone() {
        booking.setBookingStatus(BookingStatus.CANCELLED);
        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        verify(cancellationService, never()).cancel(anyString(), any());
    }

    @Test
    void paxTypeComesFromTheDateOfBirthAtTheFirstFlight() {
        assertThat(PlatformBookingMirrorTenantService.paxType(PaxInput.builder().type("ADULT").dob(LocalDate.of(2020, 1, 1)).build(),
                LocalDate.of(2026, 12, 2))).isEqualTo(PaxType.CHILD);
        assertThat(PlatformBookingMirrorTenantService.paxType(PaxInput.builder().type("INFANT").build(), null)).isEqualTo(PaxType.INFANT);
    }
}
