package com.voyra.crm.service;

import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingRefundUpdateRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.FeedbackRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
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
    private FileStorageService fileStorageService;
    @Mock
    private AuditService auditService;
    @Mock
    private LeadTimelineService leadTimelineService;
    @Mock
    private ServiceInstanceService serviceInstanceService;
    @Mock
    private ServiceBookingStatusSync serviceBookingStatusSync;

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
}
