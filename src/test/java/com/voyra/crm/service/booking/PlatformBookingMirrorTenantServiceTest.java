package com.voyra.crm.service.booking;

import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.HotelStaySummary;
import com.voyra.crm.dto.ItinerarySegment;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SupplierInvoice;
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
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.BookingCancellationService;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.ExchangeRateProvider;
import com.voyra.crm.service.SupplierInvoiceService;
import com.voyra.crm.service.SupplierPaymentService;
import com.voyra.crm.util.MarkupMath;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant phases of the CRM mirror: ensure writes the PENDING row once under the platform booking's lock, ticket
 * confirms it and pays its bill from the Voyra wallet once, fail and cancel close it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformBookingMirrorTenantServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private BookingCancellationService cancellationService;
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierPaymentService supplierPaymentService;
    @Mock private VendorRepository vendorRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private PlatformBookingQueryService queryService;
    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @Mock private com.voyra.crm.repository.ClientRepository clientRepository;
    @Mock private com.voyra.crm.service.LeadService leadAccess;
    @Mock private com.voyra.crm.service.ServiceInstanceService serviceAccess;
    @Mock private com.voyra.crm.repository.AgentRepository agentRepository;

    @InjectMocks private PlatformBookingMirrorTenantService service;

    /** The voucher confirmVoyraTicket describes (the one ensure saved, as BookingService finds it). */
    private final BookingDocument ticket = BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET)
            .purchaseCurrency("INR").purchasePrice(new BigDecimal("5400.00")).purchaseRate(BigDecimal.ONE).build();
    private final AtomicReference<SupplierInvoiceStatus> billStatus = new AtomicReference<>(SupplierInvoiceStatus.DRAFT);
    private final Booking booking = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED).build();

    @BeforeEach
    void setUp() {
        when(queryService.currentMarkup(anyString())).thenReturn(BigDecimal.ZERO);
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(Agent.builder().id("A1").build()));
        when(vendorRepository.findByIsSystemTrueAndIsActiveTrue())
                .thenReturn(Optional.of(Vendor.builder().id("V-VOYRA").name("Voyra Platform").isActive(true).isSystem(true).build()));
        when(exchangeRateProvider.resolve(eq("INR"), eq("INR"), any()))
                .thenReturn(new ResolvedExchangeRate(BigDecimal.ONE, FxRateSource.INR_IDENTITY, LocalDate.now()));
        when(bookingService.createVoyraPending(any(), any(), anyString())).thenReturn("B1");
        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenAnswer(i -> {
            i.<Consumer<BookingDocument>>getArgument(3).accept(ticket);
            return "SI1";
        });
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(booking));
        when(bookingRepository.findByPlatformBookingId(anyString())).thenReturn(Optional.empty());
        when(supplierInvoiceRepository.findById("SI1")).thenAnswer(i -> Optional.of(SupplierInvoice.builder()
                .id("SI1").vendorId("V-VOYRA").status(billStatus.get()).build()));
        when(supplierInvoiceService.approveForBooking(eq("SI1"), anyString())).thenAnswer(i -> {
            billStatus.set(SupplierInvoiceStatus.APPROVED);
            return SupplierInvoiceResponse.builder().id("SI1").status(SupplierInvoiceStatus.APPROVED)
                    .balanceDue(new BigDecimal("5400.00")).build();
        });
        when(supplierPaymentService.pay(any())).thenAnswer(i -> {
            billStatus.set(SupplierInvoiceStatus.PAID);
            return null;
        });
    }

    private static PlatformBooking platformBooking() {
        return PlatformBooking.builder().id("PB1").tenantId("T1").clientId("C1").bookedBy("A1").leadId("L1").serviceId("S1")
                .product(com.voyra.crm.enums.PricingProduct.FLIGHT).status(PlatformBookingStatus.TICKETED)
                .ticketedAt(Instant.parse("2026-11-01T10:00:00Z")).pnr("ABC123").ticketNumbers(List.of("0981", "0982"))
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

    private BookingCreateRequest pendingRequest() {
        ArgumentCaptor<BookingCreateRequest> request = ArgumentCaptor.forClass(BookingCreateRequest.class);
        verify(bookingService).createVoyraPending(request.capture(), any(), eq("PB1"));
        return request.getValue();
    }

    private BookingDocument pendingVoucher() {
        ArgumentCaptor<BookingDocument> voucher = ArgumentCaptor.forClass(BookingDocument.class);
        verify(bookingService).createVoyraPending(any(), voucher.capture(), eq("PB1"));
        return voucher.getValue();
    }

    private static BigDecimal selling(BookingDocument v) {
        return MarkupMath.sellingPrice(v.getNetCost(), v.getMarkupMode(), v.getMarkupValue());
    }

    // ---------------------------------------------------------------- ensure

    @Test
    void ensureWritesAPendingFlightMirrorWithNoReference() {
        String id = service.ensure(platformBooking(), roundTrip(), null, pax());

        assertThat(id).isEqualTo("B1");
        BookingCreateRequest r = pendingRequest();
        assertThat(r.getType()).isEqualTo(BookingType.FLIGHT);
        assertThat(r.getClientId()).isEqualTo("C1");
        assertThat(r.getAgentId()).isEqualTo("A1");
        assertThat(r.getLeadId()).isEqualTo("L1");
        assertThat(r.getServiceId()).isEqualTo("S1");
        assertThat(r.getVendorId()).isEqualTo("V-VOYRA");
        assertThat(r.getSupplier()).isEqualTo("Voyra Platform");
        assertThat(r.getNotes()).isEqualTo("Booked with Voyra");
        assertThat(r.getPnr()).isNull();
        assertThat(r.getTicketNo()).isNull();
        assertThat(r.getFlightFrom()).isEqualTo("DEL");
        assertThat(r.getDestination()).isEqualTo("BOM");
        assertThat(r.getTripType()).isEqualTo("Round Trip");
        assertThat(r.getJourneyDate()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(r.getReturnDate()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(r.getOverrideCreditLimit()).isTrue();
        assertThat(r.getPassengers()).hasSize(2);
        assertThat(r.getPassengers().get(0).getSectors()).hasSize(2);

        BookingDocument v = pendingVoucher();
        assertThat(v.getId()).isNotBlank();
        assertThat(v.getDocType()).isEqualTo(BookingDocumentType.E_TICKET);
        assertThat(v.getPurchaseCurrency()).isEqualTo("INR");
        assertThat(v.getPurchasePrice()).isEqualByComparingTo("5400.00");
        assertThat(v.getPurchaseRate()).isEqualByComparingTo("1");
        assertThat(v.getNetCost()).isEqualByComparingTo("5400.00");
        assertThat(v.getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(v.getCharges()).containsEntry("basic", new BigDecimal("4400.00")).containsEntry("otherTax", new BigDecimal("1000.00"));
        assertThat(v.getReferenceNumber()).isNull();
        assertThat(v.getIssuedDate()).isNull();
        assertThat(v.getCreatedBy()).isEqualTo("system");
        verify(bookingService, never()).confirmVoyraTicket(any(), any(), any(), any());
        verify(supplierPaymentService, never()).pay(any());
    }

    @Test
    void ensureUsesThePublishedFareAsTheDefaultPrice() {
        service.ensure(platformBooking(), roundTrip(), null, pax());

        BookingDocument v = pendingVoucher();
        assertThat(v.getMarkupValue()).isEqualByComparingTo("100.00"); // gross - displayed: the agency's discount
        assertThat(selling(v)).isEqualByComparingTo("5500");
    }

    @Test
    void ensureWithNoPublishedFareUsesNoMarkup() {
        PlatformBooking pb = platformBooking();
        pb.setGross(null);

        service.ensure(pb, roundTrip(), null, pax());

        BookingDocument v = pendingVoucher();
        assertThat(v.getMarkupValue()).isEqualByComparingTo("0");
        assertThat(selling(v)).isEqualByComparingTo("5400");
        assertThat(v.getCharges()).isNull();
    }

    @Test
    void ensureCarriesTheCurrentMarkup() {
        when(queryService.currentMarkup("PB1")).thenReturn(new BigDecimal("500.00")); // newest ticket download or Set markup

        service.ensure(platformBooking(), roundTrip(), null, pax());

        BookingDocument v = pendingVoucher();
        assertThat(v.getMarkupValue()).isEqualByComparingTo("600.00");
        assertThat(selling(v)).isEqualByComparingTo("6000"); // gross + 500
    }

    @Test
    void theMarkupInRupeesIsTheDiscountPlusTheTicketMarkupAndNeverNegative() {
        PlatformBooking pb = platformBooking();
        assertThat(PlatformBookingMirrorTenantService.markupInr(pb, new BigDecimal("50.25"))).isEqualByComparingTo("150.25");
        pb.setGross(null);
        assertThat(PlatformBookingMirrorTenantService.markupInr(pb, BigDecimal.ZERO)).isEqualByComparingTo("0");
        pb.setGross(new BigDecimal("5000.00")); // a published fare below the displayed one (never expected)
        assertThat(PlatformBookingMirrorTenantService.markupInr(pb, BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    /** A USD-base agency buys in INR at today's rate; the markup is the published fare in base minus the cost in base. */
    @Test
    void aNonInrAgencyEnsuresAtTodaysRate() {
        org.mockito.Mockito.doReturn("USD").when(baseCurrency).code();
        when(exchangeRateProvider.resolve(eq("INR"), eq("USD"), any()))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("0.012"), FxRateSource.MANUAL, LocalDate.now()));

        service.ensure(platformBooking(), roundTrip(), null, pax());

        BookingDocument v = pendingVoucher();
        assertThat(v.getPurchaseRate()).isEqualByComparingTo("0.012");
        assertThat(v.getNetCost()).isEqualByComparingTo("64.80");
        assertThat(v.getMarkupValue()).isEqualByComparingTo("1.20"); // 5500 INR = 66.00, less 64.80
    }

    @Test
    void ensureReturnsTheExistingRowUnderTheLock() {
        when(bookingRepository.findByPlatformBookingId("PB1")).thenReturn(Optional.of(booking));

        assertThat(service.ensure(platformBooking(), roundTrip(), null, pax())).isEqualTo("B1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository);
        order.verify(bookingRepository).lockPlatformBooking("PB1");
        order.verify(bookingRepository).findByPlatformBookingId("PB1");
        assertThat(service.ensure(hotelBooking(), List.of(), null, pax())).isEqualTo("B1"); // a retry never needs the stay again
        verify(bookingService, never()).createVoyraPending(any(), any(), any());
        verify(vendorRepository, never()).findByIsSystemTrueAndIsActiveTrue();
    }

    @Test
    void withNoActiveVoyraVendorTheMirrorFailsClearly() {
        when(vendorRepository.findByIsSystemTrueAndIsActiveTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensure(platformBooking(), roundTrip(), null, pax()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no active Voyra Platform vendor");
        verify(bookingService, never()).createVoyraPending(any(), any(), any());
    }

    // ---------------------------------------------------------------- ticket

    @Test
    void ticketDescribesTheTicketAndPaysFromTheVoyraWallet() {
        service.ticket("B1", platformBooking(), roundTrip(), pax());

        verify(bookingService).confirmVoyraTicket(eq("B1"), eq("ABC123"), eq("0981, 0982"), any());
        assertThat(ticket.getIssuedDate()).isEqualTo(LocalDate.now());
        assertThat(ticket.getLegs()).hasSize(2);
        assertThat(ticket.getTravellers()).extracting(t -> t.getName() + "/" + t.getPaxType() + "/" + t.getMemberId())
                .containsExactly("Mr Rahul Sharma/ADULT/M1", "Anya Sharma/CHILD/null");
        assertThat(ticket.getDetails()).containsEntry("flightFrom", "DEL");

        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getPaymentMode()).isEqualTo(PaymentMode.VOYRA_WALLET);
        assertThat(pay.getValue().getVendorId()).isEqualTo("V-VOYRA");
        assertThat(pay.getValue().getSupplierInvoiceId()).isEqualTo("SI1");
        assertThat(pay.getValue().getAmount()).isEqualByComparingTo("5400.00");
        assertThat(pay.getValue().getInstrumentRef()).isEqualTo("ABC123");
        assertThat(pay.getValue().getSettlementFxRate()).isEqualByComparingTo("1"); // no voucher found: the INR identity
    }

    @Test
    void ticketTwicePaysTheBillOnce() {
        service.ticket("B1", platformBooking(), roundTrip(), pax());
        service.ticket("B1", platformBooking(), roundTrip(), pax());

        verify(supplierInvoiceService, times(1)).approveForBooking(eq("SI1"), anyString());
        verify(supplierPaymentService, times(1)).pay(any());
    }

    @Test
    void anApprovedOrCancelledBillIsNeverPaidAgain() {
        for (SupplierInvoiceStatus s : List.of(SupplierInvoiceStatus.APPROVED, SupplierInvoiceStatus.PARTIALLY_PAID,
                SupplierInvoiceStatus.PAID, SupplierInvoiceStatus.CANCELLED)) {
            billStatus.set(s);
            service.ticket("B1", platformBooking(), roundTrip(), pax());
        }

        verify(supplierPaymentService, never()).pay(any());
    }

    /** The ticket phase never looks a rate up: it pays at the rate the voucher was bought at. */
    @Test
    void aNonInrAgencyPaysAtTheVouchersStoredRate() {
        org.mockito.Mockito.doReturn("USD").when(baseCurrency).code();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingDocument.builder().docType(BookingDocumentType.OTHER).purchaseRate(new BigDecimal("99")).build(),
                ticket.toBuilder().purchaseRate(new BigDecimal("0.012")).build()));

        service.ticket("B1", platformBooking(), roundTrip(), pax());

        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getSettlementFxRate()).isEqualByComparingTo("0.012");
        verify(exchangeRateProvider, never()).resolve(any(), any(), any());
    }

    /**
     * Set markup (spec Part 3): published 5500 + markup 500 = 6000 INR and the displayed 5400 cost, both at the voucher's
     * stored EUR rate 0.011 (66.00 - 59.40); today's rate is never looked up again.
     */
    @Test
    void markupRepricesAtTheVouchersStoredRate() {
        org.mockito.Mockito.doReturn("EUR").when(baseCurrency).code();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                ticket.toBuilder().purchaseRate(new BigDecimal("0.011")).build()));
        PlatformBooking pb = platformBooking();
        pb.setTenantBookingId("B1");

        service.setMarkup(pb, new BigDecimal("500.00"));

        verify(bookingService).repriceVoyraMarkup("B1", new BigDecimal("6.60"));
        verify(exchangeRateProvider, never()).resolve(any(), any(), any());
    }

    /** Markup 0 sets an INR agency's price back to the published fare: the FLAT markup is the discount. */
    @Test
    void aZeroMarkupIsThePublishedFare() {
        PlatformBooking pb = platformBooking();
        pb.setTenantBookingId("B1");

        service.setMarkup(pb, BigDecimal.ZERO);

        verify(bookingService).repriceVoyraMarkup("B1", new BigDecimal("100.00"));
    }

    /** A ticket downloaded while the CRM row was pending is priced when the ticket phase confirms the row. */
    @Test
    void theTicketPhasePricesTheCurrentMarkup() {
        Booking pending = Booking.builder().id("B1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(pending));
        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenAnswer(i -> {
            pending.setBookingStatus(BookingStatus.CONFIRMED);
            return "SI1";
        });
        when(queryService.currentMarkup("PB1")).thenReturn(new BigDecimal("500.00"));

        service.ticket("B1", platformBooking(), roundTrip(), pax());

        verify(bookingService).repriceVoyraMarkup("B1", new BigDecimal("600.00")); // discount 100 + markup 500
    }

    /** A row already confirmed keeps its price (an old-style row moves only on Set markup), and a really cancelled one is left alone. */
    @Test
    void theTicketPhaseRepricesOnlyTheRowItConfirmed() {
        Booking row = Booking.builder().id("B1").bookingStatus(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(row));
        service.ticket("B1", platformBooking(), roundTrip(), pax());

        row.setBookingStatus(BookingStatus.CANCELLED); // confirmVoyraTicket leaves a really cancelled row cancelled
        service.ticket("B1", platformBooking(), roundTrip(), pax());

        verify(bookingService, never()).repriceVoyraMarkup(any(), any());
    }

    @Test
    void aTicketWithoutAnApprovableBillIsNotPaid() {
        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.ticket("B1", platformBooking(), roundTrip(), pax()))
                .isInstanceOf(IllegalStateException.class).hasMessage("The mirror's supplier bill was not drafted");

        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenReturn("SI-GONE");
        when(supplierInvoiceRepository.findById("SI-GONE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ticket("B1", platformBooking(), roundTrip(), pax()))
                .isInstanceOf(IllegalStateException.class).hasMessage("The mirror's supplier bill was not drafted");

        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenReturn("SI1");
        when(supplierInvoiceService.approveForBooking(eq("SI1"), anyString())).thenReturn(SupplierInvoiceResponse.builder()
                .id("SI1").status(SupplierInvoiceStatus.PENDING_APPROVAL).build());
        assertThatThrownBy(() -> service.ticket("B1", platformBooking(), roundTrip(), pax()))
                .isInstanceOf(IllegalStateException.class).hasMessage("The mirror's supplier bill could not be approved (PENDING_APPROVAL)");
        verify(supplierPaymentService, never()).pay(any());
    }

    @Test
    void failCancelsThroughTheBookingService() {
        service.fail("B1");

        verify(bookingService).failVoyraBooking("B1");
    }

    // ---------------------------------------------------------------- concurrency (Review Focus 1)

    /**
     * Two syncs at once (the confirm thread and a repair job on another instance), each running ensure then ticket.
     * The advisory lock and the booking row lock are held until the phase's transaction ends, as in Postgres; the first
     * sync waits inside ensure until the second has asked for the lock, so a lookup taken before the lock would write twice.
     */
    @Test
    void concurrentSyncsWriteOneBookingAndOnePayment() throws Exception {
        ReentrantLock advisory = new ReentrantLock();
        ReentrantLock rowLock = new ReentrantLock();
        CountDownLatch bothAsked = new CountDownLatch(2);
        AtomicReference<Booking> row = new AtomicReference<>();
        when(bookingRepository.lockPlatformBooking("PB1")).thenAnswer(i -> {
            bothAsked.countDown();
            advisory.lock();
            return 1;
        });
        when(bookingRepository.findByPlatformBookingId("PB1")).thenAnswer(i -> Optional.ofNullable(row.get()));
        when(bookingService.createVoyraPending(any(), any(), eq("PB1"))).thenAnswer(i -> {
            assertThat(bothAsked.await(5, TimeUnit.SECONDS)).isTrue();
            row.set(Booking.builder().id("B1").bookingStatus(BookingStatus.PENDING).build());
            return "B1";
        });
        when(bookingService.confirmVoyraTicket(eq("B1"), any(), any(), any())).thenAnswer(i -> {
            rowLock.lock();
            if (row.get().getBookingStatus() == BookingStatus.PENDING) {
                row.get().setBookingStatus(BookingStatus.CONFIRMED);
                billStatus.set(SupplierInvoiceStatus.DRAFT);
            }
            return "SI1";
        });
        PlatformBooking pb = platformBooking();
        java.util.concurrent.Callable<String> sync = () -> {
            String id;
            try {
                id = service.ensure(pb, roundTrip(), null, pax());
            } finally {
                release(advisory);
            }
            try {
                service.ticket(id, pb, roundTrip(), pax());
            } finally {
                release(rowLock);
            }
            return id;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(sync);
            Future<String> b = pool.submit(sync);

            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo("B1");
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo("B1");
        } finally {
            pool.shutdownNow();
        }
        verify(bookingService, times(1)).createVoyraPending(any(), any(), any());
        verify(supplierPaymentService, times(1)).pay(any());
    }

    private static void release(ReentrantLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- hotels: one HOTEL_VOUCHER, same money path as a flight

    private static PlatformBooking hotelBooking() {
        PlatformBooking pb = platformBooking();
        pb.setProduct(com.voyra.crm.enums.PricingProduct.HOTEL);
        pb.setPnr("HC-778"); // the hotel's confirmation number
        pb.setTicketNumbers(List.of());
        pb.setItinerary(java.util.Map.of("rooms", List.of(java.util.Map.of("roomName", "Deluxe Sea View"))));
        return pb;
    }

    private static HotelStaySummary stay() {
        return new HotelStaySummary("Sea Breeze Resort", "Goa", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 2, 2, "Breakfast included");
    }

    /** Review Focus 4: the hotel mirror is a HOTEL booking with a HOTEL_VOUCHER carrying the stay - not an E_TICKET. */
    @Test
    void ensureWritesAPendingHotelMirrorCarryingTheStay() {
        assertThat(service.ensure(hotelBooking(), List.of(), stay(), pax())).isEqualTo("B1");

        BookingCreateRequest r = pendingRequest();
        assertThat(r.getType()).isEqualTo(BookingType.HOTEL);
        assertThat(r.getAgentId()).isEqualTo("A1");
        assertThat(r.getVendorId()).isEqualTo("V-VOYRA");
        assertThat(r.getHotelConfirmationNo()).isNull();
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
        assertThat(r.getOverrideCreditLimit()).isTrue();
        assertThat(r.getPassengers()).extracting(p -> p.getPassengerName()).containsExactly("Mr Rahul Sharma", "Anya Sharma");
        assertThat(r.getPassengers().get(0).getSectors()).isNullOrEmpty();

        BookingDocument v = pendingVoucher();
        assertThat(v.getDocType()).isEqualTo(BookingDocumentType.HOTEL_VOUCHER);
        assertThat(v.getPurchasePrice()).isEqualByComparingTo("5400.00");
        assertThat(selling(v)).isEqualByComparingTo("5500");
        assertThat(v.getLegs()).isNull();
        assertThat(v.getDetails()).containsEntry("hotelName", "Sea Breeze Resort").containsEntry("hotelCity", "Goa")
                .containsEntry("hotelCheckIn", "2026-12-01").containsEntry("hotelCheckOut", "2026-12-03")
                .containsEntry("hotelRooms", "2").containsEntry("hotelBoardBasis", "Breakfast included")
                .containsEntry("hotelRoomType", "Deluxe Sea View");
    }

    @Test
    void aHotelTicketTakesTheConfirmationNumberKeepsTheStayAndPays() {
        ticket.setDocType(BookingDocumentType.HOTEL_VOUCHER);
        ticket.setDetails(java.util.Map.of("hotelName", "Sea Breeze Resort"));

        service.ticket("B1", hotelBooking(), List.of(), pax());

        verify(bookingService).confirmVoyraTicket(eq("B1"), eq("HC-778"), eq(null), any());
        assertThat(ticket.getIssuedDate()).isEqualTo(LocalDate.now());
        assertThat(ticket.getDetails()).containsExactly(java.util.Map.entry("hotelName", "Sea Breeze Resort"));
        assertThat(ticket.getLegs()).isNull();
        assertThat(ticket.getTravellers()).isNull();
        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getInstrumentRef()).isEqualTo("HC-778");
    }

    // ---------------------------------------------------------------- coverage: sparse mirrors and their refusals

    @Test
    void aMirrorWithoutLegsOrPassengersIsPlain() {
        service.ensure(platformBooking(), List.of(), null, List.of());
        service.ticket("B1", platformBooking(), List.of(), List.of());

        BookingCreateRequest r = pendingRequest();
        assertThat(r.getTripType()).isNull();
        assertThat(r.getDestination()).isEqualTo("Flight");
        assertThat(ticket.getLegs()).isNull();
        assertThat(ticket.getTravellers()).isNull();
    }

    @Test
    void aOneWayMirrorWithShortTimesAndUnknownPaxTypes() {
        List<ItinerarySegment> oneWay = List.of(new ItinerarySegment("DEL", "BOM", "2026-12-02", null, "IndiGo", "6E-201"));

        service.ensure(platformBooking(), oneWay, null, List.of(PaxInput.builder().type("SENIOR").firstName("Pax").build(),
                PaxInput.builder().firstName("Nobody").build()));

        BookingCreateRequest r = pendingRequest();
        assertThat(r.getTripType()).isEqualTo("One Way");
        assertThat(r.getFlightDepartTime()).isNull();
        assertThat(r.getPassengers()).extracting(p -> p.getPaxType()).containsExactly(PaxType.UNKNOWN, PaxType.UNKNOWN);
    }

    @Test
    void undatedTravellersUseTheirDateOfBirthOnlyWithAFlight() {
        service.ensure(platformBooking(), List.of(), null, List.of(PaxInput.builder().type("CHILD").title(" ").firstName("Pax")
                .lastName("Child").dob(LocalDate.of(2020, 1, 1)).build()));

        assertThat(pendingRequest().getPassengers()).singleElement().satisfies(p -> {
            assertThat(p.getPaxType()).isEqualTo(PaxType.CHILD);
            assertThat(p.getPassengerName()).isEqualTo("Pax Child");
        });
    }

    @Test
    void aSegmentWithoutADateCannotBeMirrored() {
        assertThatThrownBy(() -> service.ensure(platformBooking(),
                List.of(new ItinerarySegment("DEL", "BOM", "soon", "x", "IndiGo", "6E-1")), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Flight 1 needs from, to and the travel date");
    }

    @Test
    void aHotelWithoutACompleteStayCannotBeMirrored() {
        PlatformBooking pb = hotelBooking();
        HotelStaySummary noOut = new HotelStaySummary("H", "Goa", LocalDate.of(2026, 12, 1), null, 1, 1, null);
        HotelStaySummary noIn = new HotelStaySummary("H", "Goa", null, LocalDate.of(2026, 12, 2), 1, 1, null);

        for (HotelStaySummary s : java.util.Arrays.asList(null, noIn, noOut)) {
            assertThatThrownBy(() -> service.ensure(pb, List.of(), s, pax()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Platform booking PB1 has no stay to mirror");
        }
        verify(bookingService, never()).createVoyraPending(any(), any(), any());
    }

    @Test
    void aHotelStayWithoutCityNameOrRoomsIsNamedHotelAndCarriesNoRoomName() {
        PlatformBooking pb = hotelBooking();
        pb.setItinerary(java.util.Map.of("rooms", List.of("not a map")));
        HotelStaySummary bare = new HotelStaySummary(" ", null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 2, null, null);

        service.ensure(pb, List.of(), bare, List.of());

        BookingCreateRequest r = pendingRequest();
        assertThat(r.getDestination()).isEqualTo("Hotel");
        assertThat(r.getHotelRoomType()).isNull();
        assertThat(r.getHotelRooms()).isNull();
    }

    @Test
    void theRoomNameIsReadOnlyFromAFirstRoomThatNamesIt() {
        HotelStaySummary stay = new HotelStaySummary("H", "Goa", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 2, 1, null);
        java.util.List<java.util.Map<String, Object>> itineraries = new java.util.ArrayList<>(List.of(java.util.Map.of(),
                java.util.Map.of("rooms", List.of()), java.util.Map.of("rooms", "x"), java.util.Map.of("rooms", List.of(java.util.Map.of("adults", 2)))));
        itineraries.add(null);
        for (java.util.Map<String, Object> itinerary : itineraries) {
            PlatformBooking pb = hotelBooking();
            pb.setItinerary(itinerary);
            org.mockito.Mockito.clearInvocations(bookingService);
            service.ensure(pb, List.of(), stay, List.of());
            assertThat(pendingRequest().getHotelRoomType()).isNull();
        }
    }

    /** Whoever booked owns the CRM booking: the owner's id is the tenant id, which BookingService resolves to the owner. */
    @Test
    void ownerBookingMirrorsUnderOwner() {
        // BookingService is mocked here; BookingServiceCoverageTest.systemActorWithTenantIdResolvesOwner proves tenant id -> owner author
        PlatformBooking pb = platformBooking();
        pb.setBookedBy("T1");

        service.ensure(pb, roundTrip(), null, pax());

        assertThat(pendingRequest().getAgentId()).isEqualTo("T1");
    }

    /** A lead with its own agent does not change who owns the CRM booking: whoever booked; a deleted booker falls back to the owner. */
    @Test
    void theBookerOwnsTheMirrorAndADeletedBookerFallsBackToTheOwner() {
        service.ensure(platformBooking(), roundTrip(), null, pax());
        assertThat(pendingRequest().getAgentId()).isEqualTo("A1");

        org.mockito.Mockito.clearInvocations(bookingService);
        PlatformBooking gone = platformBooking();
        gone.setBookedBy("A-GONE");
        service.ensure(gone, roundTrip(), null, pax());
        assertThat(pendingRequest().getAgentId()).isEqualTo("T1");
    }

    // ---------------------------------------------------------------- cancel (unchanged), links and helpers

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

    @Test
    void cancellingAMissingMirrorOrOneWithoutARefundFigureIsRefused() {
        when(bookingRepository.findByIdForUpdate("B9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel("B9", platformBooking()))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessage("Booking not found: B9");

        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(null);
        assertThatThrownBy(() -> service.cancel("B1", pb)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform booking PB1 was cancelled without a refund figure - settle the mirror by hand");
        verify(cancellationService, never()).cancel(anyString(), any());
    }

    @Test
    void aZeroRefundCancelRecordsNoWalletRefundAndSkipsOtherVouchers() {
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                BookingDocument.builder().docType(BookingDocumentType.OTHER).purchaseRate(new BigDecimal("99")).build(),
                BookingDocument.builder().docType(BookingDocumentType.E_TICKET).purchaseRate(null).build()));
        PlatformBooking pb = platformBooking();
        pb.setCancelRefund(BigDecimal.ZERO);

        service.cancel("B1", pb);

        ArgumentCaptor<BookingCancelRequest> request = ArgumentCaptor.forClass(BookingCancelRequest.class);
        verify(cancellationService).cancel(eq("B1"), request.capture());
        assertThat(request.getValue().getSupplierCharge()).isEqualByComparingTo("5400.00");
        verify(cancellationService, never()).recordVoyraWalletRefund(anyString(), any());
    }

    @Test
    void chargesNeedTheFullFareAndABasicThatIsNotNegative() {
        PlatformBooking pb = platformBooking();
        assertThat(PlatformBookingMirrorTenantService.charges(pb.toBuilder().base(null).build())).isNull();
        assertThat(PlatformBookingMirrorTenantService.charges(pb.toBuilder().displayed(null).build())).isNull();
        assertThat(PlatformBookingMirrorTenantService.charges(pb.toBuilder().displayed(new BigDecimal("500")).build())).isNull();
        assertThat(PlatformBookingMirrorTenantService.charges(pb.toBuilder().base(new BigDecimal("6000")).build()))
                .containsExactly(java.util.Map.entry("basic", new BigDecimal("5400.00")));
    }

    @Test
    void aSingleOverlongTicketNumberIsCut() {
        assertThat(PlatformBookingMirrorTenantService.ticketNo(null)).isNull();
        assertThat(PlatformBookingMirrorTenantService.ticketNo(List.of())).isNull();
        assertThat(PlatformBookingMirrorTenantService.ticketNo(List.of("9".repeat(60)))).isEqualTo("9".repeat(49) + "…");
    }

    @Test
    void blankTicketNumbersAddNoSeparatorSoEveryRealNumberCanFitBeforeTheEllipsis() {
        List<String> numbers = new java.util.ArrayList<>(java.util.Collections.nCopies(25, ""));
        numbers.add("0981234567890");

        assertThat(PlatformBookingMirrorTenantService.ticketNo(numbers)).isEqualTo("0981234567890…");
    }

    @Test
    void withoutAClientOnlyTheLeadAndServiceAreChecked() {
        when(leadAccess.findAccessibleLead("L1")).thenReturn(com.voyra.crm.entity.Lead.builder().id("L1").clientId("C1").build());
        com.voyra.crm.entity.LeadService s1 = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").clientId("C1").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(s1));

        service.requireLinks(" ", "L1", null);
        service.requireLinks(null, null, "S1");

        verify(clientRepository, never()).existsById(any());
        verify(serviceAccess).assertEditAccess(s1);
    }
}
