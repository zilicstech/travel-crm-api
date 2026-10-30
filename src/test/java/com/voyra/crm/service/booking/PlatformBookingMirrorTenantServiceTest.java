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
    @Mock private com.voyra.crm.repository.ClientRepository clientRepository;
    @Mock private com.voyra.crm.service.LeadService leadAccess;
    @Mock private com.voyra.crm.service.ServiceInstanceService serviceAccess;

    @InjectMocks private PlatformBookingMirrorTenantService service;

    private final AtomicReference<BookingDocument> ticket = new AtomicReference<>();
    private final Booking booking = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED).build();

    @BeforeEach
    void setUp() {
        when(vendorRepository.findByIsSystemTrueAndIsActiveTrue())
                .thenReturn(Optional.of(Vendor.builder().id("V-VOYRA").name("Voyra Platform").isActive(true).isSystem(true).build()));
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
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(booking));
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
    void withNoActiveVoyraVendorTheMirrorFailsClearly() {
        when(vendorRepository.findByIsSystemTrueAndIsActiveTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(platformBooking(), roundTrip(), pax()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no active Voyra Platform vendor");
        verify(bookingService, never()).logBooking(any(), anyString(), any(), any(), any(), any(), any(), any());
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

    /** Two syncs at once (the request hook and a repair job on another instance): the second waits on the lock, sees CANCELLED. */
    @Test
    void aMirrorAlreadyCancelledIsLeftAloneAfterTheLock() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED).build()));
        booking.setBookingStatus(BookingStatus.CANCELLED);
        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        verify(bookingRepository).findByIdForUpdate("B1");
        verify(cancellationService, never()).cancel(anyString(), any());
        verify(cancellationService, never()).recordVoyraWalletRefund(anyString(), any());
    }

    @Test
    void cancelTakesTheBookingLockBeforeAnythingElse() {
        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, cancellationService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        order.verify(cancellationService).cancel(eq("B1"), any());
        verify(bookingRepository, never()).findById("B1");
    }

    /** A USD-base agency bought the ticket in INR at the ticket's rate: the cancel and the refund are in its base, like create. */
    @Test
    void aNonInrAgencyCancelsInItsBaseCurrency() {
        org.mockito.Mockito.doReturn("USD").when(baseCurrency).code();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(BookingDocument.builder()
                .id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).purchaseCurrency("INR")
                .purchasePrice(new BigDecimal("5400.00")).purchaseRate(new BigDecimal("0.012")).build()));
        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        ArgumentCaptor<BookingCancelRequest> request = ArgumentCaptor.forClass(BookingCancelRequest.class);
        verify(cancellationService).cancel(eq("B1"), request.capture());
        assertThat(request.getValue().getSupplierCharge()).isEqualByComparingTo("16.80"); // 1400 INR at 0.012
        verify(cancellationService).recordVoyraWalletRefund("B1", new BigDecimal("48.00"));
    }

    /** Rate 0.5: displayed 1.02 buys 0.51, refund 1.01 is 0.51 (0.505 up); rounding the 0.01 kept separately gave 0.01 and a refund bigger than the bill. */
    @Test
    void theSupplierChargeAndTheRefundAlwaysAddUpToWhatTheMirrorBought() {
        org.mockito.Mockito.doReturn("USD").when(baseCurrency).code();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(BookingDocument.builder()
                .id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).purchaseCurrency("INR")
                .purchasePrice(new BigDecimal("1.02")).purchaseRate(new BigDecimal("0.5")).build()));
        PlatformBooking pb = platformBooking();
        pb.setDisplayed(new BigDecimal("1.02"));
        pb.setCancelRefund(new BigDecimal("1.01"));

        service.cancel("B1", pb);

        ArgumentCaptor<BookingCancelRequest> request = ArgumentCaptor.forClass(BookingCancelRequest.class);
        verify(cancellationService).cancel(eq("B1"), request.capture());
        assertThat(request.getValue().getSupplierCharge()).isEqualByComparingTo("0.00");
        verify(cancellationService).recordVoyraWalletRefund("B1", new BigDecimal("0.51"));
    }

    @Test
    void ticketNumbersAreNeverCutMidWay() {
        assertThat(PlatformBookingMirrorTenantService.ticketNo(List.of("0981234567890", "0981234567891")))
                .isEqualTo("0981234567890, 0981234567891");
        assertThat(PlatformBookingMirrorTenantService.ticketNo(List.of("0981234567890", "0981234567891", "0981234567892", "0981234567893")))
                .isEqualTo("0981234567890, 0981234567891, 0981234567892…");
        assertThat(PlatformBookingMirrorTenantService.ticketNo(List.of())).isNull();
    }

    @Test
    void paxTypeComesFromTheDateOfBirthAtTheFirstFlight() {
        assertThat(PlatformBookingMirrorTenantService.paxType(PaxInput.builder().type("ADULT").dob(LocalDate.of(2020, 1, 1)).build(),
                LocalDate.of(2026, 12, 2))).isEqualTo(PaxType.CHILD);
        assertThat(PlatformBookingMirrorTenantService.paxType(PaxInput.builder().type("INFANT").build(), null)).isEqualTo(PaxType.INFANT);
    }

    // ---- hotels: one HOTEL_VOUCHER, same money path as a flight ----

    private static PlatformBooking hotelBooking() {
        PlatformBooking pb = platformBooking();
        pb.setProduct(com.voyra.crm.enums.PricingProduct.HOTEL);
        pb.setPnr("HC-778"); // the hotel's confirmation number
        pb.setTicketNumbers(List.of());
        pb.setItinerary(java.util.Map.of("rooms", List.of(java.util.Map.of("roomName", "Deluxe Sea View"))));
        return pb;
    }

    private static com.voyra.crm.dto.HotelStaySummary stay() {
        return new com.voyra.crm.dto.HotelStaySummary("Sea Breeze Resort", "Goa", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 3), 2, 2, "Breakfast included");
    }

    /** Review Focus 4: the hotel mirror is a HOTEL booking with a HOTEL_VOUCHER carrying the stay - not an E_TICKET. */
    @Test
    void hotelMirrorIsAConfirmedVoyraHotelVoucherPaidFromTheVoyraWallet() {
        when(markupDefaultRepository.findById(ServiceType.HOTEL)).thenReturn(Optional.of(MarkupDefault.builder()
                .serviceType(ServiceType.HOTEL).mode(MarkupMode.PERCENT).value(new BigDecimal("10.00")).build()));

        String id = service.createHotel(hotelBooking(), stay(), pax());

        assertThat(id).isEqualTo("B1");
        ArgumentCaptor<BookingCreateRequest> request = ArgumentCaptor.forClass(BookingCreateRequest.class);
        ArgumentCaptor<FxMath.Pricing> pricing = ArgumentCaptor.forClass(FxMath.Pricing.class);
        verify(bookingService).logBooking(request.capture(), anyString(), any(), any(), any(),
                eq(BookingDocumentType.HOTEL_VOUCHER), eq("HC-778"), pricing.capture());
        BookingCreateRequest r = request.getValue();
        assertThat(r.getType()).isEqualTo(BookingType.HOTEL);
        assertThat(r.getAgentId()).isEqualTo("A1");
        assertThat(r.getVendorId()).isEqualTo("V-VOYRA");
        assertThat(r.getHotelConfirmationNo()).isEqualTo("HC-778");
        assertThat(r.getPnr()).isNull();
        assertThat(r.getHotelName()).isEqualTo("Sea Breeze Resort");
        assertThat(r.getHotelCity()).isEqualTo("Goa");
        assertThat(r.getHotelCheckIn()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(r.getHotelCheckOut()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(r.getHotelRooms()).isEqualTo(2);
        assertThat(r.getHotelBoardBasis()).isEqualTo("Breakfast included");
        assertThat(r.getHotelRoomType()).isEqualTo("Deluxe Sea View");
        assertThat(r.getJourneyDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(r.getReturnDate()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(r.getDestination()).isEqualTo("Goa");
        assertThat(r.getNetCost()).isEqualByComparingTo("5400.00");
        assertThat(r.getMarkupMode()).isEqualTo(MarkupMode.PERCENT); // the agency's default hotel markup
        assertThat(r.getMarkupValue()).isEqualByComparingTo("10.00");
        assertThat(r.getOverrideCreditLimit()).isTrue();
        assertThat(r.getPassengers()).extracting(p -> p.getPassengerName()).containsExactly("Mr Rahul Sharma", "Anya Sharma");
        assertThat(r.getPassengers().get(0).getSectors()).isNullOrEmpty();
        assertThat(pricing.getValue().purchase().amount()).isEqualByComparingTo("5400.00");

        BookingDocument doc = ticket.get();
        assertThat(doc.getPurchaseCurrency()).isEqualTo("INR");
        assertThat(doc.getPurchasePrice()).isEqualByComparingTo("5400.00");
        assertThat(doc.getPurchaseRate()).isEqualByComparingTo("1");
        assertThat(doc.getLegs()).isNull();
        assertThat(doc.getDetails()).containsEntry("hotelName", "Sea Breeze Resort").containsEntry("hotelCity", "Goa")
                .containsEntry("hotelCheckIn", "2026-12-01").containsEntry("hotelCheckOut", "2026-12-03")
                .containsEntry("hotelRooms", "2").containsEntry("hotelBoardBasis", "Breakfast included")
                .containsEntry("hotelRoomType", "Deluxe Sea View");
        assertThat(booking.getPlatformBookingId()).isEqualTo("PB1");

        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getPaymentMode()).isEqualTo(PaymentMode.VOYRA_WALLET);
        assertThat(pay.getValue().getSupplierInvoiceId()).isEqualTo("SI1");
        assertThat(pay.getValue().getInstrumentRef()).isEqualTo("HC-778");
    }

    @Test
    void anExistingHotelMirrorIsReturnedNotWrittenTwice() {
        when(bookingRepository.findByPlatformBookingId("PB1")).thenReturn(Optional.of(booking));

        assertThat(service.createHotel(hotelBooking(), stay(), pax())).isEqualTo("B1");
        assertThat(service.createHotel(hotelBooking(), null, pax())).isEqualTo("B1"); // a retry never needs the stay again

        verify(bookingService, never()).logBooking(any(), anyString(), any(), any(), any(), any(), any(), any());
        verify(supplierPaymentService, never()).pay(any());
    }

    /** A USD-base agency cancels its hotel mirror at the voucher's own rate, through the same cancel as a flight. */
    @Test
    void aHotelMirrorCancelsAtTheVouchersRate() {
        org.mockito.Mockito.doReturn("USD").when(baseCurrency).code();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(BookingDocument.builder()
                .id("D1").bookingId("B1").docType(BookingDocumentType.HOTEL_VOUCHER).purchaseCurrency("INR")
                .purchasePrice(new BigDecimal("5400.00")).purchaseRate(new BigDecimal("0.012")).build()));
        PlatformBooking pb = hotelBooking();
        pb.setStatus(PlatformBookingStatus.CANCELLED);
        pb.setCancelRefund(new BigDecimal("4000.00"));

        service.cancel("B1", pb);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, cancellationService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        ArgumentCaptor<BookingCancelRequest> request = ArgumentCaptor.forClass(BookingCancelRequest.class);
        order.verify(cancellationService).cancel(eq("B1"), request.capture());
        assertThat(request.getValue().getSupplierCharge()).isEqualByComparingTo("16.80"); // 1400 INR at 0.012
        verify(cancellationService).recordVoyraWalletRefund("B1", new BigDecimal("48.00"));
    }

    /** Slice 3 minor 5: a review's client, lead and service must exist in the caller's tenant, and the agent must reach them. */
    @Test
    void requireLinksChecksTheClientLeadAndServiceInTheCallersTenant() {
        when(clientRepository.existsById("C1")).thenReturn(true);
        when(leadAccess.findAccessibleLead("L1")).thenReturn(com.voyra.crm.entity.Lead.builder().id("L1").clientId("C1").build());
        com.voyra.crm.entity.LeadService s1 = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").clientId("C1").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(s1));

        service.requireLinks("C1", "L1", "S1");
        service.requireLinks(null, null, null);
        verify(serviceAccess).assertEditAccess(s1);

        // another tenant's (or a made-up) client is simply not found here
        assertThatThrownBy(() -> service.requireLinks("C-OTHER", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Client not found");
        when(leadServiceRepository.findById("S-OTHER")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireLinks("C1", null, "S-OTHER"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Service not found");
        // the lead belongs to another client, the service to another lead
        assertThatThrownBy(() -> service.requireLinks("C2", "L1", null)).isInstanceOf(IllegalArgumentException.class);
        when(clientRepository.existsById("C2")).thenReturn(true);
        assertThatThrownBy(() -> service.requireLinks("C2", "L1", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lead");
        when(leadAccess.findAccessibleLead("L2")).thenReturn(com.voyra.crm.entity.Lead.builder().id("L2").clientId("C1").build());
        assertThatThrownBy(() -> service.requireLinks("C1", "L2", "S1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service");
        // an agent who may not reach the lead or edit the service is refused (403)
        when(leadAccess.findAccessibleLead("L3")).thenThrow(new org.springframework.security.access.AccessDeniedException("This lead is not accessible to you"));
        assertThatThrownBy(() -> service.requireLinks("C1", "L3", null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("no")).when(serviceAccess).assertEditAccess(s1);
        assertThatThrownBy(() -> service.requireLinks("C1", "L1", "S1")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /** Final review minor 7: a service sent without a lead must still be the chosen client's. */
    @Test
    void aServiceWithoutALeadMustBelongToTheChosenClient() {
        when(clientRepository.existsById("C2")).thenReturn(true);
        com.voyra.crm.entity.LeadService s1 = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").clientId("C1").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(s1));

        assertThatThrownBy(() -> service.requireLinks("C2", null, "S1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This service belongs to another client");
        verify(serviceAccess, never()).assertEditAccess(any());

        when(clientRepository.existsById("C1")).thenReturn(true);
        service.requireLinks("C1", null, "S1");
        verify(serviceAccess).assertEditAccess(s1);
    }
}
