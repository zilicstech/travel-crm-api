package com.voyra.crm.service;

import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
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

    @Mock
    private SupplierInvoiceService supplierInvoiceService;
    @Mock
    private com.voyra.crm.repository.SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private com.voyra.crm.repository.SupplierPaymentRepository supplierPaymentRepository;

    @Mock
    private com.voyra.crm.repository.VendorRepository vendorRepository;
    @Mock
    private com.voyra.crm.repository.InvoiceLineItemRepository invoiceLineItemRepository;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @org.mockito.Mock

    private ClientCreditService clientCreditService;

    @Mock
    private BookingAccountingSync bookingAccountingSync;


    @org.mockito.Spy


    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);



    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void authenticateAsOwner() {
        TestInvoiceLines.into(bookingService, invoiceRepository, invoiceLineItemRepository, bookingDocumentRepository);
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    /** Runs the delete's transactional block inline, as the real template would. */
    @BeforeEach
    void runTransactionsInline() {
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAgentCannotCreateAStandaloneBookingOfATypeTheyDoNotHandle() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "priya", UserType.AGENT, "T1"), null, java.util.List.of()));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(com.voyra.crm.entity.Agent.builder().id("A1").name("Priya")
                .manageableServices(java.util.List.of(com.voyra.crm.enums.ServiceType.VISA, com.voyra.crm.enums.ServiceType.TRANSFER)).build()));
        com.voyra.crm.dto.BookingCreateRequest request = new com.voyra.crm.dto.BookingCreateRequest();
        request.setClientId("C1");
        request.setType(com.voyra.crm.enums.BookingType.FLIGHT);

        assertThatThrownBy(() -> bookingService.createBooking(request))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("FLIGHT");
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * R21: an owner logging a booking on an unassigned service for a named agent - the sync assigns the service to that
     * agent, and the booking's service-agent snapshot follows in the same transaction (it was left blank).
     */
    @Test
    void aBookingOnAnUnassignedServiceSnapshotsTheAgentTheServiceIsGivenTo() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A2", "vikram", UserType.AGENT, "T1"), null, java.util.List.of()));
        com.voyra.crm.entity.Agent vikram = com.voyra.crm.entity.Agent.builder().id("A2").name("Vikram Rao").build();
        when(agentRepository.findById("A2")).thenReturn(Optional.of(vikram));
        when(clientRepository.findById("C1")).thenReturn(Optional.of(com.voyra.crm.entity.Client.builder().id("C1").name("Asha").build()));
        com.voyra.crm.entity.LeadService service = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1")
                .type(com.voyra.crm.enums.ServiceType.FLIGHT).build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(service));
        org.mockito.Mockito.doAnswer(i -> {
            service.setAssignedAgentId(i.getArgument(1));
            service.setAssignedAgentName(i.getArgument(2));
            return null;
        }).when(serviceBookingStatusSync).onBookingLogged("S1", "A2", "Vikram Rao");
        org.mockito.ArgumentCaptor<Booking> saved = org.mockito.ArgumentCaptor.forClass(Booking.class);
        com.voyra.crm.dto.BookingCreateRequest request = new com.voyra.crm.dto.BookingCreateRequest();
        request.setClientId("C1");
        request.setAgentId("A2");
        request.setServiceId("S1");
        request.setType(com.voyra.crm.enums.BookingType.FLIGHT);

        bookingService.createBooking(request);

        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Booking last = saved.getValue();
        assertThat(last.getServiceAgentId()).isEqualTo("A2");
        assertThat(last.getServiceAgentName()).isEqualTo("Vikram Rao");
    }

    /** A CRM booking mirrored from a platform booking is changed only by the platform (spec 6). */
    @Test
    void crmEditOfAMirrorIs409() {
        Booking mirror = Booking.builder().id("B1").agentId("A1").platformBookingId("PB1")
                .bookingStatus(BookingStatus.CONFIRMED).paymentStatusSource(PaymentStatusSource.MANUAL).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(mirror));
        BookingStatusUpdateRequest confirm = new BookingStatusUpdateRequest();
        confirm.setBookingStatus(BookingStatus.CONFIRMED);
        BookingPaymentStatusUpdateRequest paid = new BookingPaymentStatusUpdateRequest();
        paid.setPaymentStatus(PaymentStatus.PAID);

        java.util.List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> edits = java.util.List.of(
                () -> bookingService.updateBooking("B1", new com.voyra.crm.dto.BookingUpdateRequest()),
                () -> bookingService.updateStatus("B1", confirm),
                () -> bookingService.updateDeadlines("B1", new com.voyra.crm.dto.BookingDeadlineUpdateRequest()),
                () -> bookingService.updatePaymentStatus("B1", paid),
                () -> bookingService.deleteBooking("B1"),
                () -> bookingService.removeVoucher("B1", "D1"),
                () -> bookingService.lockAccessibleBooking("B1"),
                () -> bookingService.replaceCostComponents("B1", java.util.List.of()));
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable edit : edits) {
            assertThatThrownBy(edit).isInstanceOf(com.voyra.crm.exception.BookingConflictException.class)
                    .hasMessage("Managed from Platform Bookings")
                    .extracting("code").isEqualTo("MANAGED_FROM_PLATFORM");
        }
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theSystemActorMayWriteAMirror() {
        Booking mirror = Booking.builder().id("B1").agentId("A1").platformBookingId("PB1").build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(mirror));

        assertThat(com.voyra.crm.security.SystemActor.call("T1", () -> bookingService.lockAccessibleBooking("B1"))).isSameAs(mirror);
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
    void statusPatchNoLongerCancelsABookingOnItsOwn() {
        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CANCELLED);
        request.setCancelReason("Client changed plans");

        assertThatThrownBy(() -> bookingService.updateStatus("B1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/cancel");
    }

    @Test
    void editingCostComponentsOnAConfirmedBookingRaisesChangeAlerts() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CONFIRMED)
                .sellingPrice(new BigDecimal("44000.00")).netCost(new BigDecimal("40000.00"))
                .profit(new BigDecimal("4000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingCostComponentRepository.existsById(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        com.voyra.crm.dto.BookingCostComponentRequest component = new com.voyra.crm.dto.BookingCostComponentRequest();
        component.setNetCost(new BigDecimal("30000.00"));
        bookingService.replaceCostComponents("B1", List.of(component));

        org.mockito.ArgumentCaptor<List<com.voyra.crm.entity.AccountingChangeAlert>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(accountingChangeAlertRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(com.voyra.crm.entity.AccountingChangeAlert::getFieldName)
                .contains("netCost", "profit");
    }

    @Test
    void confirmingABookingWithNoCostComponentsSkipsThePriceIntegrityCheck() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .type(com.voyra.crm.enums.BookingType.FLIGHT).pnr("ABC123").sellingPrice(new BigDecimal("50000.00")).build();
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
                .type(com.voyra.crm.enums.BookingType.FLIGHT).pnr("ABC123").sellingPrice(new BigDecimal("50000.00"))
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
                .type(com.voyra.crm.enums.BookingType.FLIGHT).pnr("ABC123").sellingPrice(new BigDecimal("45000.00"))
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
    void aHotelCannotCheckOutBeforeItChecksIn() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .hotelCheckIn(java.time.LocalDate.of(2026, 12, 12)).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setHotelCheckOut(java.time.LocalDate.of(2026, 12, 10));

        assertThatThrownBy(() -> bookingService.updateBooking("B1", request)).hasMessage("Check-out must be after check-in");
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).save(booking);
    }

    @Test
    void emptyNotesClearTheBookingsNotes() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).notes("CRB2 edited").build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setNotes("");

        bookingService.updateBooking("B1", request);

        assertThat(booking.getNotes()).isNull();
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

    @Test
    void deletingABookingCancelsItsDraftSupplierBill() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        com.voyra.crm.entity.SupplierInvoice draft = com.voyra.crm.entity.SupplierInvoice.builder()
                .id("S1").status(com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(draft));

        bookingService.deleteBooking("B1");

        org.mockito.Mockito.verify(supplierInvoiceService).cancelForBooking(org.mockito.ArgumentMatchers.eq("S1"), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(bookingRepository).delete(booking);
    }

    @Test
    void aBookingWithAnApprovedSupplierBillCannotBeDeleted() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        com.voyra.crm.entity.SupplierInvoice approved = com.voyra.crm.entity.SupplierInvoice.builder()
                .id("S1").status(com.voyra.crm.enums.SupplierInvoiceStatus.APPROVED).build();
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(approved));

        assertThatThrownBy(() -> bookingService.deleteBooking("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancel it instead");
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).delete(booking);
    }

    @Test
    void deletingABookingKeepsItsVoucherFiles() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        bookingService.deleteBooking("B1");

        // Soft delete: the rows are marked, and the stored files are never removed.
        org.mockito.Mockito.verify(bookingRepository).delete(booking);
        org.mockito.Mockito.verify(fileStorageService, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aMissingOrDeletedBookingIsNotFound() {
        when(bookingRepository.findById("GONE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getBooking("GONE"))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessageContaining("Booking not found");
    }

    @Test
    void aBookingPastPendingCannotBeDeleted() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.deleteBooking("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only a pending booking can be deleted");
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).delete(booking);
    }

    private static Booking pendingFlight(String pnr, String sellingPrice) {
        return Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .type(com.voyra.crm.enums.BookingType.FLIGHT).pnr(pnr)
                .netCost(new BigDecimal("4000.00")).sellingPrice(new BigDecimal(sellingPrice)).build();
    }

    private static BookingStatusUpdateRequest confirm() {
        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CONFIRMED);
        return request;
    }

    @Test
    void confirmingNeedsTheBookingReference() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(pendingFlight(null, "5000.00")));

        assertThatThrownBy(() -> bookingService.updateStatus("B1", confirm()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Add the booking reference and selling price before confirming");
    }

    @Test
    void confirmingNeedsASellingPrice() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(pendingFlight("ABC123", "0")));

        assertThatThrownBy(() -> bookingService.updateStatus("B1", confirm()))
                .hasMessage("Add the booking reference and selling price before confirming");
    }

    @Test
    void aHotelIsConfirmedByItsConfirmationNumber() {
        Booking hotel = pendingFlight("ignored", "5000.00");
        hotel.setType(com.voyra.crm.enums.BookingType.HOTEL);
        hotel.setPnr(null);
        hotel.setHotelConfirmationNo("HTL-1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(hotel));

        assertThat(bookingService.updateStatus("B1", confirm()).getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void confirmingDraftsTheSupplierBillOnce() {
        Booking booking = pendingFlight("ABC123", "5000.00");
        booking.setVendorId("V1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of());

        bookingService.updateStatus("B1", confirm());

        org.mockito.Mockito.verify(supplierInvoiceService).createAutoDraft(booking, "V1");
    }

    @Test
    void confirmingKeepsAnExistingSupplierBill() {
        Booking booking = pendingFlight("ABC123", "5000.00");
        booking.setVendorId("V1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(com.voyra.crm.entity.SupplierInvoice.builder().id("S1").build()));

        bookingService.updateStatus("B1", confirm());

        org.mockito.Mockito.verify(supplierInvoiceService, org.mockito.Mockito.never())
                .createAutoDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateSetsTheVendorAndDescription() {
        Booking booking = pendingFlight(null, "0");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));
        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setVendorId("V1");
        request.setDestination("DEL → DXB");

        bookingService.updateBooking("B1", request);

        assertThat(booking.getVendorId()).isEqualTo("V1");
        assertThat(booking.getSupplier()).isEqualTo("TripJack");
        assertThat(booking.getDestination()).isEqualTo("DEL → DXB");
    }

    @Test
    void confirmingLocksTheBookingBeforeDraftingItsBill() {
        Booking booking = pendingFlight("ABC123", "5000.00");
        booking.setVendorId("V1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        bookingService.updateStatus("B1", confirm());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, supplierInvoiceService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        order.verify(supplierInvoiceService).createAutoDraft(booking, "V1");
    }

    @Test
    void aSupplierPickedAfterConfirmingDraftsTheirBill() {
        Booking booking = pendingFlight("ABC123", "5000.00");
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));
        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setVendorId("V1");

        bookingService.updateBooking("B1", request);

        org.mockito.Mockito.verify(supplierInvoiceService).createAutoDraft(booking, "V1");
    }

    // ---- vouchers carry the money --------------------------------------------------------

    private static com.voyra.crm.entity.BookingDocument voucher(String cost, String markupPercent, String reference) {
        return com.voyra.crm.entity.BookingDocument.builder().id("D1").bookingId("B1").referenceNumber(reference)
                .netCost(new BigDecimal(cost)).markupMode(MarkupMode.PERCENT).markupValue(new BigDecimal(markupPercent)).build();
    }

    private static Booking pendingWithoutFare() {
        return Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.PENDING)
                .type(com.voyra.crm.enums.BookingType.FLIGHT).netCost(BigDecimal.ZERO).sellingPrice(BigDecimal.ZERO).build();
    }

    @Test
    void aPricedVoucherWithAReferenceConfirmsTheBookingAndAddsItsMoney() {
        Booking booking = pendingWithoutFare();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));
        when(supplierInvoiceService.draftForVoucher(booking, "V1", "PNR123", null, new BigDecimal("4000"), null, BigDecimal.ZERO, BigDecimal.ZERO))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S1").build());
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR123");

        bookingService.applyVoucher(booking, v, "V1", false);

        assertThat(v.getSellingPrice()).isEqualByComparingTo("4400");
        assertThat(booking.getNetCost()).isEqualByComparingTo("4000");
        assertThat(booking.getSellingPrice()).isEqualByComparingTo("4400");
        assertThat(booking.getProfit()).isEqualByComparingTo("400");
        assertThat(booking.getPnr()).isEqualTo("PNR123");
        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(v.getSupplierInvoiceId()).isEqualTo("S1");
        // Made without a supplier: it takes the voucher's, so it can be invoiced with that supplier's other bookings.
        assertThat(booking.getVendorId()).isEqualTo("V1");
        assertThat(booking.getSupplier()).isEqualTo("TripJack");
    }

    /** Regression C1: a voucher added to (or taken off) an invoiced booking changes what is to collect, so its payment status is re-derived. */
    @Test
    void aVoucherOnAnInvoicedBookingReDerivesItsPaymentStatus() {
        Booking booking = pendingWithoutFare();
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setPnr("PNR0");
        booking.setVendorId("V1");
        booking.setPaymentStatusSource(com.voyra.crm.enums.PaymentStatusSource.DERIVED);
        com.voyra.crm.entity.BookingDocument v = voucher("1000", "50", "PNR0");
        when(supplierInvoiceService.draftForVoucher(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S1").build());

        bookingService.applyVoucher(booking, v, "V1", false);
        bookingService.reverseVoucher(booking, v, false);

        org.mockito.Mockito.verify(bookingAccountingSync, org.mockito.Mockito.times(2)).syncPayment("B1");
    }

    /** An uninvoiced booking keeps its manually set payment status - nothing is re-derived. */
    @Test
    void aVoucherOnAnUninvoicedBookingLeavesItsPaymentStatusAlone() {
        Booking booking = pendingWithoutFare();
        when(supplierInvoiceService.draftForVoucher(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S1").build());
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));

        bookingService.applyVoucher(booking, voucher("1000", "50", "PNR0"), "V1", false);

        org.mockito.Mockito.verify(bookingAccountingSync, org.mockito.Mockito.never()).syncPayment(org.mockito.ArgumentMatchers.any());
    }

    /** A flight voucher's commission is the agency's: it goes on the booking's profit and on the supplier bill. */
    @Test
    void aVoucherWithCommissionAddsItToTheBookingsProfitAndItsBill() {
        Booking booking = pendingWithoutFare();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));
        com.voyra.crm.entity.BookingDocument v = voucher("10000", "10", "PNR1");
        v.setCharges(new java.util.LinkedHashMap<>(java.util.Map.of("basic", new BigDecimal("8200"), "yq", new BigDecimal("1800"))));
        BookingService.applyCommission(v, MarkupMode.PERCENT, new BigDecimal("5"), null);
        when(supplierInvoiceService.draftForVoucher(booking, "V1", "PNR1", null, new BigDecimal("10000"), null,
                new BigDecimal("410.00"), new BigDecimal("8.20")))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S1").build());

        bookingService.applyVoucher(booking, v, "V1", false);

        assertThat(booking.getCommission()).isEqualByComparingTo("410");
        // 11,000 - 10,000 + 410 commission.
        assertThat(booking.getProfit()).isEqualByComparingTo("1410");
        assertThat(v.getSupplierInvoiceId()).isEqualTo("S1");

        bookingService.reverseVoucher(booking, v, false);
        assertThat(booking.getCommission()).isEqualByComparingTo("0");
        assertThat(booking.getProfit()).isEqualByComparingTo("0");
    }

    @Test
    void commissionIsOnFlightsWithASupplierOnly() {
        Booking hotel = pendingWithoutFare();
        hotel.setType(com.voyra.crm.enums.BookingType.HOTEL);
        com.voyra.crm.entity.BookingDocument v = voucher("10000", "10", "HV1");
        BookingService.applyCommission(v, MarkupMode.FLAT, new BigDecimal("300"), null);
        assertThatThrownBy(() -> bookingService.applyVoucher(hotel, v, "V1", false)).hasMessageContaining("flight tickets only");

        Booking flight = pendingWithoutFare();
        assertThatThrownBy(() -> bookingService.applyVoucher(flight, v, null, false)).hasMessageContaining("Pick the supplier");
    }

    /** The accountant's commission moves the booking's profit and the voucher's bill; hotels take none. */
    @Test
    void theAccountantsCommissionMovesTheProfitAndTheBill() {
        Booking booking = pendingWithoutFare();
        booking.setSellingPrice(new BigDecimal("11000"));
        booking.setNetCost(new BigDecimal("10000"));
        com.voyra.crm.entity.BookingDocument v = voucher("10000", "10", "PNR1");
        v.setSupplierInvoiceId("S1");
        com.voyra.crm.entity.BookingDocument priced = com.voyra.crm.entity.BookingDocument.builder().netCost(new BigDecimal("10000")).build();
        BookingService.applyCommission(priced, MarkupMode.FLAT, new BigDecimal("410"), null);

        bookingService.changeCommission(booking, v, priced);

        assertThat(booking.getCommission()).isEqualByComparingTo("410");
        assertThat(booking.getProfit()).isEqualByComparingTo("1410");
        org.mockito.Mockito.verify(supplierInvoiceService).setDraftCommission("S1", new BigDecimal("410.00"), new BigDecimal("8.20"));

        booking.setType(com.voyra.crm.enums.BookingType.HOTEL);
        assertThatThrownBy(() -> bookingService.changeCommission(booking, v, priced)).hasMessageContaining("flight tickets only");
    }

    /** Bought in AED: commission and TDS stay in AED like the bill; the booking gets the commission in base. */
    @Test
    void aForeignVouchersCommissionIsInItsCurrencyAndInBaseOnTheBooking() {
        com.voyra.crm.entity.BookingDocument v = voucher("22850", "10", "PNR1");
        v.setPurchaseCurrency("AED");
        v.setPurchasePrice(new BigDecimal("1000"));
        v.setPurchaseRate(new BigDecimal("22.85"));

        BookingService.applyCommission(v, MarkupMode.FLAT, new BigDecimal("50"), new BigDecimal("5"));

        assertThat(v.getCommission()).isEqualByComparingTo("50");
        assertThat(v.getSupplierTds()).isEqualByComparingTo("2.50");
        assertThat(v.getCommissionInr()).isEqualByComparingTo("1142.50");
    }

    /** One supplier per booking: beside another voucher, a voucher with a different supplier is refused. */
    @Test
    void aSecondVoucherWithAnotherSupplierIsRefused() {
        Booking booking = pendingWithoutFare();
        booking.setVendorId("V1");
        booking.setSupplier("TripJack");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                com.voyra.crm.entity.BookingDocument.builder().id("D0").bookingId("B1").vendorId("V1").build()));
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR2");

        assertThatThrownBy(() -> bookingService.applyVoucher(booking, v, "V2", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("with TripJack");
        assertThat(booking.getNetCost()).isEqualByComparingTo("0");
    }

    /** The booking's only voucher moved to another supplier: the booking follows it. */
    @Test
    void theOnlyVoucherSetsTheBookingsSupplier() {
        Booking booking = pendingWithoutFare();
        booking.setVendorId("V1");
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR1");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(v));
        when(vendorRepository.findById("V2")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V2").name("Akbar").build()));
        when(supplierInvoiceService.draftForVoucher(booking, "V2", "PNR1", null, new BigDecimal("4000"), null, BigDecimal.ZERO, BigDecimal.ZERO))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S1").build());

        bookingService.applyVoucher(booking, v, "V2", false);

        assertThat(booking.getVendorId()).isEqualTo("V2");
        assertThat(booking.getSupplier()).isEqualTo("Akbar");
    }

    @Test
    void aBookingWithVouchersKeepsTheirSupplier() {
        Booking booking = pendingFlight(null, "0");
        booking.setVendorId("V1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(live("D1", "PNR1", "4400")));
        BookingUpdateRequest request = new BookingUpdateRequest();
        request.setVendorId("V2");

        assertThatThrownBy(() -> bookingService.updateBooking("B1", request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("change it on its voucher");
    }

    @Test
    void aVoucherBoughtInAnotherCurrencyDraftsItsBillInThatCurrency() {
        Booking booking = pendingWithoutFare();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Vendor.builder().id("V1").name("TripJack").build()));
        com.voyra.crm.entity.BookingDocument v = voucher("22850", "10", "PNR123");
        v.setPurchaseCurrency("AED");
        v.setPurchasePrice(new BigDecimal("1000"));
        v.setPurchaseRate(new BigDecimal("22.85"));
        when(supplierInvoiceService.draftForVoucher(booking, "V1", "PNR123", "AED", new BigDecimal("1000"), new BigDecimal("22.85"), BigDecimal.ZERO, BigDecimal.ZERO))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("S2").build());

        bookingService.applyVoucher(booking, v, "V1", false);

        assertThat(booking.getNetCost()).isEqualByComparingTo("22850");
        assertThat(v.getSupplierInvoiceId()).isEqualTo("S2");
    }

    @Test
    void theAccountantRemarkIsTrimmedAndABlankOneClearsIt() {
        Booking booking = pendingWithoutFare();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));

        bookingService.updateAccountantRemark("B1", "  Done (Zohaib) ");
        assertThat(booking.getAccountantRemark()).isEqualTo("Done (Zohaib)");

        bookingService.updateAccountantRemark("B1", "   ");
        assertThat(booking.getAccountantRemark()).isNull();
    }

    @Test
    void assignByIsTheLeadsCreatorElseTheBookingsCreator() {
        Booking fromLead = pendingWithoutFare();
        fromLead.setLeadId("L1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(fromLead));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(com.voyra.crm.entity.Lead.builder().id("L1")
                .createdBy("A9").createdByName("Ryan Mathew").build()));
        assertThat(bookingService.getBooking("B1").getAssignedByName()).isEqualTo("Ryan Mathew");

        Booking adHoc = pendingWithoutFare();
        adHoc.setCreatedBy("T1");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(adHoc));
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(com.voyra.crm.entity.Tenant.builder().id("T1").ownerName("Dana Owner").build()));
        assertThat(bookingService.getBooking("B1").getAssignedByName()).isEqualTo("Dana Owner");
    }

    @Test
    void aVoucherWithNoSupplierIsRefused() {
        Booking booking = pendingWithoutFare();
        com.voyra.crm.entity.BookingDocument doc = com.voyra.crm.entity.BookingDocument.builder().id("D1").referenceNumber("PNR123").build();

        assertThatThrownBy(() -> bookingService.applyVoucher(booking, doc, null, false)).hasMessage("Pick the supplier of this voucher");
    }

    @Test
    void anUnpricedDocumentLeavesTheBookingPendingAndDraftsNoBill() {
        Booking booking = pendingWithoutFare();
        booking.setVendorId("V1");
        com.voyra.crm.entity.BookingDocument doc = com.voyra.crm.entity.BookingDocument.builder().id("D1").referenceNumber("PNR123").build();

        bookingService.applyVoucher(booking, doc, null, false);

        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        org.mockito.Mockito.verifyNoInteractions(supplierInvoiceService);
    }

    @Test
    void aVoucherOverTheClientsCreditLimitChangesNothing() {
        Booking booking = pendingFlight("PNR1", "5000.00");
        booking.setClientId("C1");
        org.mockito.Mockito.doThrow(new IllegalStateException("over their credit limit"))
                .when(clientCreditService).assertWithinLimit(org.mockito.ArgumentMatchers.eq("C1"), org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("1200")) == 0), org.mockito.ArgumentMatchers.eq(false));

        assertThatThrownBy(() -> bookingService.applyVoucher(booking, voucher("1000", "20", "HTL-9"), null, false))
                .hasMessageContaining("credit limit");
        assertThat(booking.getSellingPrice()).isEqualByComparingTo("5000");
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).save(booking);
    }

    @Test
    void removingTheOnlyPricedVoucherPutsTheBookingBackToPending() {
        Booking booking = pendingFlight("PNR1", "0");
        booking.setVendorId("V1");
        org.mockito.Mockito.lenient().when(supplierInvoiceService.draftForVoucher(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI9").build());
        bookingService.applyVoucher(booking, voucher("1000", "20", "PNR1"), null, false);
        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        com.voyra.crm.entity.BookingDocument applied = voucher("1000", "20", "PNR1");
        applied.setSellingPrice(new BigDecimal("1200"));

        bookingService.reverseVoucher(booking, applied);

        assertThat(booking.getSellingPrice()).isEqualByComparingTo("0");
        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void aVoucherIsInvoicedOnlyWhenAnIssuedInvoiceCameAfterIt() {
        java.time.LocalDateTime issued = java.time.LocalDateTime.of(2026, 10, 18, 11, 0);
        com.voyra.crm.entity.BookingDocument before = com.voyra.crm.entity.BookingDocument.builder()
                .createdAt(issued.minusHours(1)).build();
        com.voyra.crm.entity.BookingDocument after = com.voyra.crm.entity.BookingDocument.builder()
                .createdAt(issued.plusHours(1)).build();
        assertThat(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of(), issued).covers(before)).isTrue();
        assertThat(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of(), issued).covers(after)).isFalse();
        assertThat(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of(), null).covers(before)).isFalse();
        // A voucher an issued invoice line names is invoiced whenever it was added.
        com.voyra.crm.entity.BookingDocument named = after.toBuilder().id("V1").build();
        assertThat(new InvoiceLineSource.Invoiced(BigDecimal.ZERO, java.util.Set.of("V1"), null).covers(named)).isTrue();
    }

    @Test
    void aCancelledBookingCannotBeReopened() {
        Booking booking = Booking.builder().id("B1").agentId("A1").bookingStatus(BookingStatus.CANCELLED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        BookingStatusUpdateRequest request = new BookingStatusUpdateRequest();
        request.setBookingStatus(BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> bookingService.updateStatus("B1", request))
                .hasMessage("A cancelled booking can't be reopened - create a new booking instead");
    }

    /**
     * R11 (regression C, invariant "a voucher-priced booking's price is the sum of its vouchers"): a booking created through
     * the API with a price takes its vouchers' money once it has one - the typed price is replaced, never added to.
     */
    @Test
    void aBookingCreatedWithAPriceIsPricedBySumOfItsVouchers() {
        Booking booking = pendingFlight(null, "5000.00"); // created with sell 5000 / net 4000, no vouchers
        booking.setVendorId("V1");
        org.mockito.Mockito.lenient().when(supplierInvoiceService.draftForVoucher(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI1").build());
        com.voyra.crm.entity.BookingDocument first = com.voyra.crm.entity.BookingDocument.builder().id("D1").bookingId("B1")
                .referenceNumber("PNR1").netCost(new BigDecimal("4000")).markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("1000")).build();

        bookingService.applyVoucher(booking, first, null, false);

        assertThat(booking.getSellingPrice()).isEqualByComparingTo("5000");
        assertThat(booking.getNetCost()).isEqualByComparingTo("4000");
        assertThat(booking.getProfit()).isEqualByComparingTo("1000");

        com.voyra.crm.entity.BookingDocument second = com.voyra.crm.entity.BookingDocument.builder().id("D2").bookingId("B1")
                .referenceNumber("PNR2").netCost(new BigDecimal("3000")).markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("500")).build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(first, second));

        bookingService.applyVoucher(booking, second, null, false);

        assertThat(booking.getSellingPrice()).isEqualByComparingTo("8500");
        assertThat(booking.getNetCost()).isEqualByComparingTo("7000");
        assertThat(booking.getProfit()).isEqualByComparingTo("1500");
    }

    @Test
    void secondVoucherAddsUpAndAlertsAccountsOnAConfirmedBooking() {
        Booking booking = pendingFlight("PNR1", "5000.00");
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setVendorId("V1");
        org.mockito.Mockito.lenient().when(supplierInvoiceService.draftForVoucher(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI9").build());

        // the booking's 4000 / 5000 is its first voucher's (R11: totals are the sum of the live vouchers)
        com.voyra.crm.entity.BookingDocument firstVoucher = com.voyra.crm.entity.BookingDocument.builder().id("D0").bookingId("B1")
                .netCost(new BigDecimal("4000.00")).sellingPrice(new BigDecimal("5000.00")).build();
        com.voyra.crm.entity.BookingDocument second = voucher("1000", "20", "HTL-9");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(firstVoucher, second));

        bookingService.applyVoucher(booking, second, null, false);

        assertThat(booking.getNetCost()).isEqualByComparingTo("5000");
        assertThat(booking.getSellingPrice()).isEqualByComparingTo("6200");
        org.mockito.Mockito.verify(accountingChangeAlertRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void removingAVoucherWithADraftBillTakesItsMoneyBack() {
        Booking booking = pendingFlight("PNR1", "4400.00");
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR1");
        v.setSellingPrice(new BigDecimal("4400"));
        v.setSupplierInvoiceId("S1");
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(com.voyra.crm.entity.SupplierInvoice.builder()
                .id("S1").status(com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT).build()));

        bookingService.reverseVoucher(booking, v);

        org.mockito.Mockito.verify(supplierInvoiceService).cancelForBooking(org.mockito.ArgumentMatchers.eq("S1"), org.mockito.ArgumentMatchers.anyString());
        assertThat(booking.getNetCost()).isEqualByComparingTo("0");
        assertThat(booking.getSellingPrice()).isEqualByComparingTo("0");
    }

    @Test
    void aVoucherWhoseBillIsApprovedCannotBeRemoved() {
        Booking booking = pendingFlight("PNR1", "4400.00");
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR1");
        v.setSellingPrice(new BigDecimal("4400"));
        v.setSupplierInvoiceId("S1");
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(com.voyra.crm.entity.SupplierInvoice.builder()
                .id("S1").status(com.voyra.crm.enums.SupplierInvoiceStatus.APPROVED).build()));

        assertThatThrownBy(() -> bookingService.reverseVoucher(booking, v)).hasMessageContaining("already approved - it can't be removed");
        assertThatThrownBy(() -> bookingService.reverseVoucher(booking, v, false)).hasMessageContaining("can't be changed");
        assertThat(booking.getSellingPrice()).isEqualByComparingTo("4400");
    }

    @Test
    void anInvoicedVoucherCannotBeRemoved() {
        Booking booking = pendingFlight("PNR1", "4400.00");
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR1");
        v.setSellingPrice(new BigDecimal("4400"));
        Invoice issued = Invoice.builder().id("I1").bookingId("B1").status(com.voyra.crm.enums.InvoiceLifecycle.ISSUED).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.eq("B1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(issued));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().lineSubtotal(new BigDecimal("4400")).build()));

        assertThatThrownBy(() -> bookingService.reverseVoucher(booking, v)).hasMessageContaining("already invoiced");
    }

    @Test
    void aVoucherOnADraftInvoiceCannotBeChangedUntilTheDraftGoes() {
        com.voyra.crm.entity.BookingDocument v = voucher("4000", "10", "PNR1");
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(
                Invoice.builder().id("I9").bookingId("B1").status(com.voyra.crm.enums.InvoiceLifecycle.DRAFT).build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I9")).thenReturn(List.of(
                com.voyra.crm.entity.InvoiceLineItem.builder().invoiceId("I9").bookingId("B1").bookingDocumentId("D1")
                        .lineSubtotal(new BigDecimal("4400")).build()));

        assertThatThrownBy(() -> bookingService.assertNotInvoiced(v)).hasMessageContaining("on a draft invoice");
    }

    // ---- CRB-8: the booking's reference follows its live vouchers ------------------------

    private static com.voyra.crm.entity.BookingDocument live(String id, String reference, String price) {
        return com.voyra.crm.entity.BookingDocument.builder().id(id).bookingId("B1").referenceNumber(reference)
                .sellingPrice(new BigDecimal(price)).createdAt(java.time.LocalDateTime.now()).build();
    }

    @Test
    void theReferenceIsThePricedVouchersNotTheFirstUnpricedOne() {
        Booking booking = pendingFlight("NOPRICE", "4400.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                live("D1", "NOPRICE", "0"), live("D2", "ET2", "4400")));

        bookingService.syncReference(booking, null);

        assertThat(booking.getPnr()).isEqualTo("ET2");
    }

    /** Many PNRs fit the 100-character reference whole: the ones that don't are counted, never cut mid-PNR. */
    @Test
    void manyVoucherReferencesAreShortenedWithACountNotCutOff() {
        Booking booking = pendingFlight(null, "0");
        List<com.voyra.crm.entity.BookingDocument> vouchers = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) vouchers.add(live("D" + i, "PNR-ABCDE" + i, "100"));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(vouchers);

        bookingService.syncReference(booking, null);

        assertThat(booking.getPnr()).hasSizeLessThanOrEqualTo(100).startsWith("PNR-ABCDE1, PNR-ABCDE2,").matches(".*PNR-ABCDE\\d+ \\+\\d+ more");
        int shown = booking.getPnr().split(", ").length;
        assertThat(booking.getPnr()).endsWith("+" + (15 - shown) + " more");
    }

    @Test
    void anEditedVoucherReferenceIsTheBookingsNewReference() {
        Booking booking = pendingFlight(null, "4400.00");
        booking.setType(com.voyra.crm.enums.BookingType.HOTEL);
        booking.setHotelConfirmationNo("HV1");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(live("D1", "HV1C", "4400")));

        bookingService.syncReference(booking, "HV1");

        assertThat(booking.getHotelConfirmationNo()).isEqualTo("HV1C");
    }

    /** A priced voucher saved without its PNR, then edited to add it: the booking is now booked. */
    @Test
    void aReferenceAddedToAPricedVoucherConfirmsThePendingBooking() {
        Booking booking = pendingFlight(null, "11000.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(live("D1", "23453453", "11000")));

        bookingService.syncReference(booking, null);

        assertThat(booking.getPnr()).isEqualTo("23453453");
        assertThat(booking.getBookingStatus()).isEqualTo(com.voyra.crm.enums.BookingStatus.CONFIRMED);
    }

    /** Saved before the fix above: reference and price already there, still Pending - the next voucher save confirms it. */
    @Test
    void aPendingBookingAlreadyHoldingItsReferenceAndPriceIsConfirmedOnTheNextSync() {
        Booking booking = pendingFlight("23453453", "11000.00");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(live("D1", "23453453", "11000")));

        bookingService.syncReference(booking, "23453453");

        assertThat(booking.getBookingStatus()).isEqualTo(com.voyra.crm.enums.BookingStatus.CONFIRMED);
    }

    @Test
    void removingTheOnlyVoucherClearsTheReferenceItGave() {
        Booking booking = pendingFlight("HV1", "0");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());

        bookingService.syncReference(booking, "HV1");

        assertThat(booking.getPnr()).isNull();
    }

    @Test
    void aReferenceTypedOnTheBookingSurvivesAVoucherWithoutOne() {
        Booking booking = pendingFlight("TYPED", "0");
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(live("D1", null, "0")));

        bookingService.syncReference(booking, null);

        assertThat(booking.getPnr()).isEqualTo("TYPED");
    }
}
