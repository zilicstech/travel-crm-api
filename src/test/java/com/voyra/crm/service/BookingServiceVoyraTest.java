package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancellationType;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AccountingChangeAlertRepository;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingCostComponentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.FeedbackRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SystemActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The mirror-only writes of a Voyra booking: pending create, ticket confirm, fail and revive. */
@ExtendWith(MockitoExtension.class)
class BookingServiceVoyraTest {

    private static final String FAIL_REASON = "Voyra could not complete this booking; nothing was charged";

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private BookingPassengerRepository bookingPassengerRepository;
    @Mock private BookingSectorRepository bookingSectorRepository;
    @Mock private BookingCostComponentRepository bookingCostComponentRepository;
    @Mock private AccountingChangeAlertRepository accountingChangeAlertRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private CustomerLedgerEntryRepository customerLedgerEntryRepository;
    @Mock private FeedbackRepository feedbackRepository;
    @Mock private AuditService auditService;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private ServiceInstanceService serviceInstanceService;
    @Mock private ServiceBookingStatusSync serviceBookingStatusSync;
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @Mock private ClientCreditService clientCreditService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private JournalService journalService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private BookingAccountingSync bookingAccountingSync;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        TestInvoiceLines.into(bookingService, invoiceRepository, invoiceLineItemRepository, bookingDocumentRepository);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "Owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static <T> T platform(java.util.function.Supplier<T> work) {
        return SystemActor.call("T1", work);
    }

    private static void platformRun(Runnable work) {
        SystemActor.call("T1", () -> {
            work.run();
            return null;
        });
    }

    private static BookingCreateRequest request() {
        BookingCreateRequest r = new BookingCreateRequest();
        r.setClientId("C1");
        r.setAgentId("A1");
        r.setType(BookingType.FLIGHT);
        r.setDestination("BOM");
        r.setVendorId("V1");
        r.setSupplier("Voyra Platform");
        r.setServiceId("S1");
        r.setLeadId("L1");
        return r;
    }

    private static BookingDocument ticket() {
        return BookingDocument.builder().id("D1").docType(BookingDocumentType.E_TICKET).vendorId("V1")
                .netCost(new BigDecimal("10000")).markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("500"))
                .purchaseCurrency("INR").purchasePrice(new BigDecimal("10000")).purchaseRate(BigDecimal.ONE).build();
    }

    private static Booking.BookingBuilder pending() {
        return Booking.builder().id("B1").bookingNumber(7L).type(BookingType.FLIGHT).bookingStatus(BookingStatus.PENDING)
                .clientId("C1").clientName("Asha").destination("BOM").agentId("A1").agentName("Priya")
                .supplier("Voyra Platform").vendorId("V1").platformBookingId("PB1")
                .netCost(new BigDecimal("10000")).sellingPrice(new BigDecimal("10500"));
    }

    private void locked(Booking b, BookingDocument... docs) {
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        if (docs.length > 0) {
            when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(b.getId())).thenReturn(List.of(docs));
        }
    }

    private void billDrafted(String id) {
        when(supplierInvoiceService.draftForVoucher(any(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(SupplierInvoiceResponse.builder().id(id).build());
    }

    @Test
    void createVoyraPendingSavesAPendingBookingWithNoBillNoTimelineNoServiceSync() {
        Agent priya = Agent.builder().id("A1").name("Priya").build();
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(priya));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).build()));
        BookingDocument voucher = ticket();

        String id = platform(() -> bookingService.createVoyraPending(request(), voucher, "PB1"));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).save(saved.capture());
        Booking b = saved.getValue();
        assertThat(b.getId()).isEqualTo(id);
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.getPlatformBookingId()).isEqualTo("PB1");
        assertThat(b.getNetCost()).isEqualByComparingTo("10000");
        assertThat(b.getSellingPrice()).isEqualByComparingTo("10500");
        assertThat(b.getProfit()).isEqualByComparingTo("500");
        assertThat(b.getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(b.getMarkupValue()).isEqualByComparingTo("500");
        assertThat(b.getPnr()).isNull();
        assertThat(voucher.getBookingId()).isEqualTo(id);
        assertThat(voucher.getSellingPrice()).isEqualByComparingTo("10500");
        assertThat(voucher.getVendorId()).isEqualTo("V1");
        assertThat(voucher.getReferenceNumber()).isNull();
        assertThat(voucher.getSupplierInvoiceId()).isNull();
        verify(bookingDocumentRepository).save(voucher);
        verifyNoInteractions(supplierInvoiceService, leadTimelineService, serviceBookingStatusSync, clientCreditService);
    }

    @Test
    void aPendingVoyraBookingNeverTakesAReferenceFromItsRequest() {
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Priya").build()));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).build()));
        BookingCreateRequest r = request();
        r.setPnr("PNR1");
        r.setHotelConfirmationNo("HCN1");

        platform(() -> bookingService.createVoyraPending(r, ticket(), "PB1"));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).save(saved.capture());
        assertThat(saved.getValue().getPnr()).isNull();
        assertThat(saved.getValue().getHotelConfirmationNo()).isNull();
    }

    @Test
    void failOnAConfirmedBookingIsANoOp() {
        Booking b = pending().bookingStatus(BookingStatus.CONFIRMED).leadId("L1").build();
        locked(b);

        platformRun(() -> bookingService.failVoyraBooking("B1"));

        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getCancelReason()).isNull();
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(auditService, leadTimelineService);
    }

    @Test
    void createVoyraPendingRefusesOutsideTheSystemActor() {
        assertThatThrownBy(() -> bookingService.createVoyraPending(request(), ticket(), "PB1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Voyra bookings are written by the platform only");
        assertThatThrownBy(() -> bookingService.confirmVoyraTicket("B1", "PNR9", null, v -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessage("Voyra bookings are written by the platform only");
        assertThatThrownBy(() -> bookingService.failVoyraBooking("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Voyra bookings are written by the platform only");
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void confirmVoyraTicketDraftsTheBillMarksTheServiceAndConfirms() {
        Booking b = pending().leadId("L1").serviceId("S1").build();
        BookingDocument voucher = ticket();
        BookingDocument other = BookingDocument.builder().id("D0").docType(BookingDocumentType.OTHER).build();
        locked(b, other, voucher);
        billDrafted("SI1");
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).assignedAgentId("A1").assignedAgentName("Priya").build()));

        String bill = platform(() -> bookingService.confirmVoyraTicket("B1", "PNR9", "098-1",
                v -> v.setIssuedDate(LocalDate.of(2026, 11, 1))));

        assertThat(bill).isEqualTo("SI1");
        verify(supplierInvoiceService, times(1)).draftForVoucher(b, "V1", "PNR9", "INR", new BigDecimal("10000"),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED,
                "Voyra Platform booking logged (PNR9)");
        verify(serviceBookingStatusSync).onBookingLogged("S1", "A1", "Priya");
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getPnr()).isEqualTo("PNR9");
        assertThat(b.getTicketNo()).isEqualTo("098-1");
        assertThat(b.getServiceAgentId()).isEqualTo("A1");
        assertThat(voucher.getReferenceNumber()).isEqualTo("PNR9");
        assertThat(voucher.getIssuedDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(voucher.getSupplierInvoiceId()).isEqualTo("SI1");
        verify(bookingDocumentRepository).save(voucher);
        verify(auditService).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void confirmVoyraTicketTwiceDraftsOneBill() {
        Booking b = pending().type(BookingType.HOTEL).build();
        BookingDocument voucher = ticket();
        voucher.setDocType(BookingDocumentType.HOTEL_VOUCHER);
        locked(b, voucher);
        billDrafted("SI1");

        assertThat(platform(() -> bookingService.confirmVoyraTicket("B1", "HCN1", null, v -> { }))).isEqualTo("SI1");
        assertThat(platform(() -> bookingService.confirmVoyraTicket("B1", "HCN1", null, v -> { }))).isEqualTo("SI1");
        b.setBookingStatus(BookingStatus.COMPLETED);
        assertThat(platform(() -> bookingService.confirmVoyraTicket("B1", "HCN1", null, v -> { }))).isEqualTo("SI1");

        verify(supplierInvoiceService, times(1)).draftForVoucher(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(b.getHotelConfirmationNo()).isEqualTo("HCN1");
        verifyNoInteractions(leadTimelineService, serviceBookingStatusSync);
    }

    @Test
    void failVoyraBookingCancelsWithNoMoneyAndLeavesTheServiceAlone() {
        Booking b = pending().leadId("L1").serviceId("S1").build();
        locked(b);

        platformRun(() -> bookingService.failVoyraBooking("B1"));
        platformRun(() -> bookingService.failVoyraBooking("B1"));

        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancelReason()).isEqualTo(FAIL_REASON);
        assertThat(b.getCancelledBy()).isEqualTo("system");
        assertThat(b.getCancelledAt()).isNotNull();
        assertThat(b.getRefundState()).isEqualTo(RefundState.NOT_APPLICABLE);
        assertThat(b.getCancellationType()).isNull();
        assertThat(b.getRefundAmount()).isNull();
        assertThat(b.getRefundDueDate()).isNull();
        // the non-null money columns stay at their zero default
        assertThat(b.getCancellationSupplierCharge()).isZero();
        assertThat(b.getCancellationAirlineCharge()).isZero();
        assertThat(b.getCancellationAgencyFee()).isZero();
        assertThat(b.getCustomerRefundDue()).isZero();
        assertThat(b.getSupplierRefundExpected()).isZero();
        verify(leadTimelineService, times(1)).record("L1", "S1", LeadTimelineEventType.BOOKING_REMOVED,
                "Voyra Platform booking cancelled: " + FAIL_REASON);
        verify(auditService, times(1)).recordUpdate(any(), any(), any(), any());
        verifyNoInteractions(serviceBookingStatusSync, supplierInvoiceService);
        assertThat(BookingService.isFailCancelled(b)).isTrue();
    }

    @Test
    void confirmVoyraTicketRevivesAFailCancelledBooking() {
        Booking b = pending().leadId("L1").build();
        BookingDocument voucher = ticket();
        locked(b, voucher);
        billDrafted("SI1");
        platformRun(() -> bookingService.failVoyraBooking("B1"));

        platform(() -> bookingService.confirmVoyraTicket("B1", "PNR9", null, v -> { }));

        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getCancelReason()).isNull();
        assertThat(b.getCancelledAt()).isNull();
        assertThat(b.getCancelledBy()).isNull();
        assertThat(b.getRefundState()).isEqualTo(RefundState.NOT_APPLICABLE);
        assertThat(BookingService.isFailCancelled(b)).isFalse();
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.BOOKING_LOGGED,
                "Voyra ticketed this booking after all");
        verify(supplierInvoiceService, times(1)).draftForVoucher(any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditService, times(2)).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void aBookingWithNoLeadFailsAndRevivesWithoutATimeline() {
        Booking b = pending().build();
        locked(b, ticket());
        billDrafted("SI1");

        platformRun(() -> bookingService.failVoyraBooking("B1"));
        platform(() -> bookingService.confirmVoyraTicket("B1", "PNR9", null, v -> { }));

        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(leadTimelineService);
    }

    @Test
    void aCancelledBookingWithCancellationMoneyIsNotRevivable() {
        Booking cancelled = pending().bookingStatus(BookingStatus.CANCELLED).cancelledBy(SystemActor.USER_ID)
                .cancellationType(CancellationType.CANCELLATION).build();
        assertThat(BookingService.isFailCancelled(cancelled)).isFalse();
        assertThat(BookingService.isFailCancelled(pending().bookingStatus(BookingStatus.CANCELLED)
                .cancelledBy(SystemActor.USER_ID).platformBookingId(null).build())).isFalse();
        assertThat(BookingService.isFailCancelled(pending().bookingStatus(BookingStatus.CANCELLED)
                .cancelledBy("O1").build())).isFalse();
        assertThat(BookingService.isFailCancelled(pending().build())).isFalse();

        // a really cancelled mirror is never confirmed again by a later sync: it keeps its bill
        BookingDocument voucher = ticket();
        voucher.setSupplierInvoiceId("SI0");
        locked(cancelled, voucher);
        assertThat(platform(() -> bookingService.confirmVoyraTicket("B1", "PNR9", null, v -> { }))).isEqualTo("SI0");
        assertThat(cancelled.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(supplierInvoiceService);
    }

    @Test
    void voyraWritesRefuseAMissingBookingOrTicket() {
        when(bookingRepository.findByIdForUpdate("B9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> platformRun(() -> bookingService.failVoyraBooking("B9")))
                .isInstanceOf(NotFoundException.class).hasMessage("Booking not found: B9");

        locked(pending().build(), BookingDocument.builder().id("D0").docType(BookingDocumentType.OTHER).build());
        assertThatThrownBy(() -> platform(() -> bookingService.confirmVoyraTicket("B1", "PNR9", null, v -> { })))
                .isInstanceOf(IllegalStateException.class).hasMessage("This Voyra booking has no ticket or voucher");
    }

    @Test
    void createBookingStillAnnouncesAsBefore() {
        Agent priya = Agent.builder().id("A1").name("Priya").build();
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(priya));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(priya));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).build()));
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        BookingCreateRequest r = request();
        r.setPnr("PNR1");

        platform(() -> bookingService.createBooking(r));

        verify(clientCreditService).assertWithinLimit("C1", BigDecimal.ZERO, false);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED,
                "Voyra Platform booking logged (PNR1)");
        verify(serviceBookingStatusSync).onBookingLogged("S1", "A1", "Priya");
        verify(bookingRepository, never()).findByIdForUpdate(any());
    }

    // ---- markup-only reprice (spec Part 3) ----

    private static BookingDocument pricedTicket() {
        BookingDocument v = ticket();
        v.setBookingId("B1");
        v.setSellingPrice(new BigDecimal("10500"));
        v.setSupplierInvoiceId("SI1");
        return v;
    }

    private static Booking.BookingBuilder confirmed() {
        return pending().bookingStatus(BookingStatus.CONFIRMED).pnr("PNR9").markupMode(MarkupMode.FLAT)
                .markupValue(new BigDecimal("500")).profit(new BigDecimal("500"));
    }

    private void invoicedAt(String price) {
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1",
                com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE, com.voyra.crm.enums.InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(com.voyra.crm.entity.Invoice.builder().id("I1").bookingId("B1")
                        .status(com.voyra.crm.enums.InvoiceLifecycle.ISSUED).build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(com.voyra.crm.entity.InvoiceLineItem.builder().lineSubtotal(new BigDecimal(price)).build()));
    }

    @Test
    void repriceMovesSellingAndProfitButNotCostOrBill() {
        Booking b = confirmed().paymentStatusSource(com.voyra.crm.enums.PaymentStatusSource.DERIVED).build();
        BookingDocument voucher = pricedTicket();
        BookingDocument other = BookingDocument.builder().id("D0").docType(BookingDocumentType.OTHER).build();
        locked(b, other, voucher);

        platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("800.40")));

        assertThat(voucher.getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(voucher.getMarkupValue()).isEqualByComparingTo("800.40");
        assertThat(voucher.getSellingPrice()).isEqualByComparingTo("10800");
        assertThat(voucher.getNetCost()).isEqualByComparingTo("10000");
        assertThat(voucher.getSupplierInvoiceId()).isEqualTo("SI1");
        assertThat(b.getSellingPrice()).isEqualByComparingTo("10800");
        assertThat(b.getProfit()).isEqualByComparingTo("800");
        assertThat(b.getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(b.getMarkupValue()).isEqualByComparingTo("800.40");
        assertThat(b.getNetCost()).isEqualByComparingTo("10000");
        assertThat(b.getVendorId()).isEqualTo("V1");
        assertThat(b.getUpdatedBy()).isEqualTo(SystemActor.USER_ID);
        verify(bookingRepository).save(b);
        verify(bookingDocumentRepository).save(voucher);
        verify(auditService).recordUpdate(any(), any(), any(), any());
        verify(bookingAccountingSync).syncPayment("B1");
        verifyNoInteractions(supplierInvoiceService, supplierInvoiceRepository, clientCreditService);
    }

    @Test
    void repriceOnACancelledBookingIs409() {
        for (BookingStatus status : List.of(BookingStatus.CANCELLED, BookingStatus.PENDING)) {
            Booking b = confirmed().bookingStatus(status).build();
            when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b));

            assertThatThrownBy(() -> platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("800"))))
                    .isInstanceOfSatisfying(com.voyra.crm.exception.BookingConflictException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("MARKUP_NOT_APPLICABLE");
                        assertThat(e.getMessage()).isEqualTo("Markup can be set only on a confirmed booking");
                    });
        }
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void repriceBelowTheInvoicedPriceIs409() {
        Booking b = confirmed().build();
        BookingDocument voucher = pricedTicket();
        locked(b, voucher);
        invoicedAt("10500");

        assertThatThrownBy(() -> platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("300"))))
                .isInstanceOfSatisfying(com.voyra.crm.exception.BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRICE_BELOW_INVOICED");
                    assertThat(e.getMessage()).isEqualTo("This price is below what you have invoiced; adjust the invoice first");
                });

        assertThat(voucher.getSellingPrice()).isEqualByComparingTo("10500");
        assertThat(voucher.getMarkupValue()).isEqualByComparingTo("500");
        assertThat(b.getSellingPrice()).isEqualByComparingTo("10500");
        verify(bookingRepository, never()).save(any());
        verify(bookingDocumentRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    /** A higher price after invoicing leaves an uninvoiced balance; a completed booking is repriced too. */
    @Test
    void repriceAboveTheInvoicedPriceIsAllowed() {
        Booking b = confirmed().bookingStatus(BookingStatus.COMPLETED).build();
        BookingDocument voucher = pricedTicket();
        locked(b, voucher);
        invoicedAt("10500");

        platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("900")));

        assertThat(b.getSellingPrice()).isEqualByComparingTo("10900");
        assertThat(b.getProfit()).isEqualByComparingTo("900");
        assertThat(voucher.getSellingPrice()).isEqualByComparingTo("10900");
        verify(bookingRepository).save(b);
        verifyNoInteractions(bookingAccountingSync); // a MANUAL payment status is left alone
    }

    /** The same markup again (or one that rounds to the same price) changes nothing and is never refused. */
    @Test
    void repriceToTheSamePriceWritesNothing() {
        Booking b = confirmed().build();
        BookingDocument voucher = pricedTicket();
        locked(b, voucher);

        platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("500")));
        platformRun(() -> bookingService.repriceVoyraMarkup("B1", new BigDecimal("500.40")));

        assertThat(voucher.getMarkupValue()).isEqualByComparingTo("500");
        verify(bookingRepository, never()).save(any());
        verify(bookingDocumentRepository, never()).save(any());
        verifyNoInteractions(auditService, invoiceRepository, bookingAccountingSync);
    }

    @Test
    void repriceRefusesOutsideTheSystemActorOrWithoutAVoucher() {
        assertThatThrownBy(() -> bookingService.repriceVoyraMarkup("B1", BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class).hasMessage("Voyra bookings are written by the platform only");

        locked(confirmed().build(), BookingDocument.builder().id("D0").docType(BookingDocumentType.OTHER).build());
        assertThatThrownBy(() -> platformRun(() -> bookingService.repriceVoyraMarkup("B1", BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class).hasMessage("This Voyra booking has no ticket or voucher");
    }
}
