package com.voyra.crm.service;

import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingRefundUpdateRequest;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
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
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Once accounting owns a booking's numbers, the manual PATCH endpoints must refuse to
 * contradict them - see {@code BookingAccountingSync}.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingDocumentRepository bookingDocumentRepository;
    @Mock
    private BookingPassengerRepository bookingPassengerRepository;
    @Mock
    private BookingSectorRepository bookingSectorRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LeadServiceRepository leadServiceRepository;
    @Mock
    private com.voyra.crm.repository.LeadRepository leadRepository;
    @Mock
    private CreditNoteRepository creditNoteRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private CustomerLedgerEntryRepository customerLedgerEntryRepository;
    @Mock
    private FeedbackRepository feedbackRepository;
    @Mock
    private BookingCostComponentRepository bookingCostComponentRepository;
    @Mock
    private AccountingChangeAlertRepository accountingChangeAlertRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private AuditService auditService;
    @Mock
    private LeadTimelineService leadTimelineService;
    @Mock
    private ServiceInstanceService serviceInstanceService;
    @Mock
    private ServiceBookingStatusSync serviceBookingStatusSync;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private JournalService journalService;
    @Mock
    private JournalEntryRepository journalEntryRepository;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualPaymentStatusPatchSucceedsWhileStillManual() {
        Booking booking = Booking.builder().id("B1").agentId("A1").paymentStatusSource(PaymentStatusSource.MANUAL).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        BookingPaymentStatusUpdateRequest request = new BookingPaymentStatusUpdateRequest();
        request.setPaymentStatus(PaymentStatus.PAID);

        var response = bookingService.updatePaymentStatus("B1", request);

        assertThat(response.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void manualPaymentStatusPatchIsRefusedOnceDerived() {
        Booking booking = Booking.builder().id("B1").agentId("A1").paymentStatusSource(PaymentStatusSource.DERIVED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        BookingPaymentStatusUpdateRequest request = new BookingPaymentStatusUpdateRequest();
        request.setPaymentStatus(PaymentStatus.PAID);

        assertThatThrownBy(() -> bookingService.updatePaymentStatus("B1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("derived from invoices");
    }

    @Test
    void manualRefundPatchSucceedsWhenNoCreditNoteExists() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CANCELLED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(creditNoteRepository.existsByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(false);

        BookingRefundUpdateRequest request = new BookingRefundUpdateRequest();
        request.setRefundState(RefundState.REFUND_PENDING);

        var response = bookingService.updateRefund("B1", request);

        assertThat(response.getRefundState()).isEqualTo(RefundState.REFUND_PENDING);
    }

    @Test
    void manualRefundPatchIsRefusedOnceACreditNoteExists() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CANCELLED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(creditNoteRepository.existsByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(true);

        BookingRefundUpdateRequest request = new BookingRefundUpdateRequest();
        request.setRefundState(RefundState.REFUNDED);

        assertThatThrownBy(() -> bookingService.updateRefund("B1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit note exists");
    }

    @Test
    void confirmingABookingWithNoCostComponentsSkipsThePriceIntegrityCheck() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .sellingPrice(new BigDecimal("50000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of());

        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CONFIRMED);

        var response = bookingService.updateStatus("B1", request);

        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void confirmingABookingIsRefusedWhenComponentsPlusMarkupDisagreeWithSellingPrice() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .sellingPrice(new BigDecimal("50000.00"))
                .markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("5000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().id("C1").bookingId("B1").serviceType(ServiceType.FLIGHT)
                        .netCostInr(new BigDecimal("40000.00")).build()));

        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CONFIRMED);

        // 40000 (components) + 5000 (flat markup) = 45000, but sellingPrice is 50000 - a real mismatch.
        assertThatThrownBy(() -> bookingService.updateStatus("B1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("variance");
    }

    @Test
    void confirmingABookingSucceedsWhenComponentsPlusMarkupMatchSellingPrice() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .sellingPrice(new BigDecimal("45000.00"))
                .markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("5000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().id("C1").bookingId("B1").serviceType(ServiceType.FLIGHT)
                        .netCostInr(new BigDecimal("40000.00")).build()));

        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CONFIRMED);

        var response = bookingService.updateStatus("B1", request);

        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void updatingAHotelBookingDerivesDepartureDateFromCheckIn() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of());

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setHotelCheckIn(java.time.LocalDate.now().plusDays(10));

        bookingService.updateBooking("B1", request);

        assertThat(booking.getDepartureDate()).isEqualTo(java.time.LocalDate.now().plusDays(10));
    }

    @Test
    void updatingATransferBookingDerivesDepartureDateFromTransferDate() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of());

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setTransferDate(java.time.LocalDate.now().plusDays(3));

        bookingService.updateBooking("B1", request);

        assertThat(booking.getDepartureDate()).isEqualTo(java.time.LocalDate.now().plusDays(3));
    }

    @Test
    void reschedulingDepartureLaterAfterRecognitionReversesTheJournal() {
        java.time.LocalDate oldDate = java.time.LocalDate.now().minusDays(5);
        java.time.LocalDate newDate = java.time.LocalDate.now().plusDays(20);
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CONFIRMED)
                .journeyDate(oldDate).departureDate(oldDate).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").build();
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of(invoice));
        JournalEntry recognitionEntry = JournalEntry.builder().id("JE1").build();
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.REVENUE_RECOGNIZED))
                .thenReturn(Optional.of(recognitionEntry));

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setJourneyDate(newDate);

        bookingService.updateBooking("B1", request);

        org.mockito.Mockito.verify(journalService).reverse(org.mockito.ArgumentMatchers.eq("JE1"), org.mockito.ArgumentMatchers.anyString());
        assertThat(booking.getDepartureDate()).isEqualTo(newDate);
    }

    @Test
    void reschedulingDepartureEarlierDoesNotReverseAnything() {
        java.time.LocalDate oldDate = java.time.LocalDate.now().plusDays(20);
        java.time.LocalDate newDate = java.time.LocalDate.now().plusDays(5);
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CONFIRMED)
                .journeyDate(oldDate).departureDate(oldDate).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setJourneyDate(newDate);

        bookingService.updateBooking("B1", request);

        org.mockito.Mockito.verifyNoInteractions(journalService);
        assertThat(booking.getDepartureDate()).isEqualTo(newDate);
    }
}
