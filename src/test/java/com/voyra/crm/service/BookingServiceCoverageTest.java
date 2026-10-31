package com.voyra.crm.service;

import com.voyra.crm.dto.AccountingChangeAlertResponse;
import com.voyra.crm.dto.BookingCostComponentRequest;
import com.voyra.crm.dto.BookingCostComponentResponse;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingDeadlineUpdateRequest;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingSectorRequest;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.AccountingChangeAlert;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceStatus;
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
import com.voyra.crm.util.FxMath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The branches of BookingService the topic tests leave out: create, log, lists, edits, vouchers, delete and responses. */
@ExtendWith(MockitoExtension.class)
class BookingServiceCoverageTest {

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
        as("O1", UserType.AGENCY_OWNER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String userId, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user-" + userId, type, "T1"), null, List.of()));
    }

    private static Booking.BookingBuilder booking() {
        return Booking.builder().id("B1").agentId("A1").type(BookingType.FLIGHT).bookingStatus(BookingStatus.PENDING)
                .bookingNumber(42L).clientId("C1").clientName("Asha").destination("Goa");
    }

    private void found(Booking b) {
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
    }

    private static BookingCreateRequest request(BookingType type) {
        BookingCreateRequest r = new BookingCreateRequest();
        r.setClientId("C1");
        r.setAgentId("A1");
        r.setType(type);
        return r;
    }

    private static Agent agent(String id, String name, List<ServiceType> handles) {
        return Agent.builder().id(id).name(name).manageableServices(handles).build();
    }

    /** The booking belongs to this agent: they are the caller (an owner's booking is the owner's own). */
    private void ownerPicks(Agent a) {
        as(a.getId(), UserType.AGENT);
        when(agentRepository.findById(a.getId())).thenReturn(Optional.of(a));
    }

    private void clientFound() {
        when(clientRepository.findById("C1")).thenReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
    }

    // ---------------------------------------------------------------- create

    @Test
    void aBookingWithNoTypeSkipsTheAgentCheckButNeedsAClientOfTheAgency() {
        ownerPicks(agent("A1", "Priya", List.of()));
        BookingCreateRequest r = request(null);
        r.setClientId("C9");

        assertThatThrownBy(() -> bookingService.createBooking(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Client not found: C9");
        verify(clientCreditService).assertWithinLimit("C9", BigDecimal.ZERO, false);
        verify(agentRepository, times(1)).findById("A1");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aBookingOnAServiceThatDoesNotExistIsRefused() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setServiceId("S9");

        assertThatThrownBy(() -> bookingService.createBooking(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service not found: S9");
        verify(agentRepository, times(1)).findById("A1");
    }

    @Test
    void aBookingMustBeOfItsServicesType() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        LeadService hotel = LeadService.builder().id("S1").leadId("L1").type(ServiceType.HOTEL).build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(hotel));
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setServiceId("S1");

        assertThatThrownBy(() -> bookingService.createBooking(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type must be HOTEL for a booking on a HOTEL service");
        verify(serviceInstanceService).assertEditAccess(hotel);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aStandaloneBookingNamingAnUnknownLeadIsRefused() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        BookingCreateRequest r = request(BookingType.PACKAGE);
        r.setServiceId("  ");
        r.setLeadId("L9");

        assertThatThrownBy(() -> bookingService.createBooking(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: L9");
        verify(leadServiceRepository, never()).findById(any());
    }

    @Test
    void aStandaloneBookingKeepsItsLeadItsPaymentStatusAndItsPassengers() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").build()));
        BookingPassenger old = BookingPassenger.builder().id("OLD").bookingId("x").build();
        BookingPassenger stored = BookingPassenger.builder().id("P1").passengerName("Pax A").paxType(PaxType.ADULT)
                .fareAmount(new BigDecimal("100.00")).taxAmount(BigDecimal.ZERO).build();
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(anyString()))
                .thenReturn(List.of(old), List.of(stored));
        when(bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(List.of("P1"))).thenReturn(List.of(
                BookingSector.builder().id("SE1").bookingPassengerId("P1").sectorFrom("DEL").sectorTo("BOM")
                        .flightNumber("AI101").cabinClass("Economy").build()));

        BookingCreateRequest r = request(BookingType.PACKAGE);
        r.setLeadId("L1");
        r.setPaymentStatus(PaymentStatus.PAID);
        r.setSupplier("Sun Tours");
        BookingSectorRequest sector = new BookingSectorRequest();
        sector.setSectorFrom("DEL");
        sector.setSectorTo("BOM");
        BookingPassengerRequest a = new BookingPassengerRequest();
        a.setPassengerName("Pax A");
        a.setPaxType(PaxType.ADULT);
        a.setFareAmount(new BigDecimal("100.00"));
        a.setSectors(List.of(sector));
        BookingPassengerRequest b = new BookingPassengerRequest();
        b.setPassengerName("Pax B");
        b.setTaxAmount(new BigDecimal("5.00"));
        r.setPassengers(List.of(a, b));

        BookingResponse response = bookingService.createBooking(r);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getLeadId()).isEqualTo("L1");
        assertThat(saved.getValue().getServiceId()).isNull();
        assertThat(saved.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(saved.getValue().getSupplier()).isEqualTo("Sun Tours");
        assertThat(saved.getValue().getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        verify(bookingSectorRepository).deleteByBookingPassengerIdIn(List.of("OLD"));
        ArgumentCaptor<BookingPassenger> pax = ArgumentCaptor.forClass(BookingPassenger.class);
        verify(bookingPassengerRepository, times(2)).save(pax.capture());
        assertThat(pax.getAllValues()).extracting(BookingPassenger::getPassengerName, BookingPassenger::getTaxAmount, BookingPassenger::getSortOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Pax A", BigDecimal.ZERO, 0),
                        org.assertj.core.groups.Tuple.tuple("Pax B", new BigDecimal("5.00"), 1));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingSector>> sectors = ArgumentCaptor.forClass(List.class);
        verify(bookingSectorRepository).saveAll(sectors.capture());
        assertThat(sectors.getValue()).singleElement().satisfies(s -> {
            assertThat(s.getSectorFrom()).isEqualTo("DEL");
            assertThat(s.getBookingPassengerId()).isEqualTo(pax.getAllValues().get(0).getId());
            assertThat(s.getSortOrder()).isZero();
        });
        assertThat(response.getPassengers()).singleElement().satisfies(p -> {
            assertThat(p.getPassengerName()).isEqualTo("Pax A");
            assertThat(p.getSectors()).singleElement().satisfies(s -> {
                assertThat(s.getSectorTo()).isEqualTo("BOM");
                assertThat(s.getFlightNumber()).isEqualTo("AI101");
            });
        });
        verifyNoInteractions(leadTimelineService);
    }

    @Test
    void thePlatformMirrorMayBookATypeTheNamedAgentDoesNotHandle() {
        Agent priya = agent("A1", "Priya", List.of(ServiceType.VISA));
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(priya));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(priya));
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();

        BookingResponse response = SystemActor.call("T1", () -> bookingService.createBooking(request(BookingType.FLIGHT)));

        assertThat(response.getAgentId()).isEqualTo("A1");
        assertThat(response.getType()).isEqualTo(BookingType.FLIGHT);
        // only the response's "assigned by" lookup reads the agent - the type check never ran
        verify(agentRepository, times(1)).findById("A1");
    }

    @Test
    void aBlankSupplierWithNoVendorIsStoredAsNoneAndTheTimelineNamesTheType() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        LeadService visa = LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).label("Dubai visa").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(visa));
        BookingCreateRequest r = request(BookingType.VISA);
        r.setServiceId("S1");
        r.setSupplier(" ");

        bookingService.createBooking(r);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getSupplier()).isNull();
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED, "Visa booking logged");
    }

    @Test
    void loggingOnAnAssignedServiceKeepsItsAgentAndNamesThePnr() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        LeadService flight = LeadService.builder().id("S1").leadId("L1").type(ServiceType.FLIGHT)
                .assignedAgentId("A2").assignedAgentName("Vikram").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flight));
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setServiceId("S1");
        r.setPnr("PNR1");
        r.setSupplier("IndiGo");

        BookingResponse response = bookingService.createBooking(r);

        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED, "IndiGo booking logged (PNR1)");
        verify(serviceBookingStatusSync).onBookingLogged("S1", "A1", "Priya");
        verify(bookingRepository, times(1)).save(any());
        assertThat(response.getServiceAgentId()).isEqualTo("A2");
        assertThat(response.getLeadId()).isEqualTo("L1");
    }

    @Test
    void anAgentWithNoRecordCannotCreateABooking() {
        as("A1", UserType.AGENT);
        assertThatThrownBy(() -> bookingService.createBooking(request(BookingType.FLIGHT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
    }

    private void ownerOfTenant() {
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").ownerName("Owner Test").build()));
    }

    @Test
    void ownerBookingBelongsToOwner() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setAgentId(null);

        bookingService.createBooking(r);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo("T1");
        assertThat(saved.getValue().getAgentName()).isEqualTo("Owner Test");
    }

    @Test
    void ownerBookingIgnoresRequestedAgent() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();

        bookingService.createBooking(request(BookingType.FLIGHT)); // names agent A1

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo("T1");
        verify(agentRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void ownerSkipsStandaloneTypeCheck() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();

        assertThat(bookingService.createBooking(request(BookingType.HOTEL)).getType()).isEqualTo(BookingType.HOTEL);
    }

    @Test
    void anOwnerWhoseAgencyIsMissingIsRefused() {
        assertThatThrownBy(() -> bookingService.createBooking(request(BookingType.FLIGHT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agency not found: T1");
    }

    @Test
    void systemActorWithTenantIdResolvesOwner() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setAgentId("T1");

        SystemActor.call("T1", () -> bookingService.createBooking(r));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo("T1");
        assertThat(saved.getValue().getAgentName()).isEqualTo("Owner Test");
    }

    @Test
    void systemActorWithoutAnAgentResolvesOwner() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setAgentId(" ");

        SystemActor.call("T1", () -> bookingService.createBooking(r));

        verify(agentRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void systemActorWithNullAgentIdResolvesOwner() {
        ownerOfTenant();
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setAgentId(null);

        SystemActor.call("T1", () -> bookingService.createBooking(r));

        verify(agentRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void systemActorWithAgentIdResolvesAgent() {
        Agent priya = agent("A1", "Priya", List.of());
        when(agentRepository.findByIdAndTenantId("A1", "T1")).thenReturn(Optional.of(priya));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(priya));
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();

        SystemActor.call("T1", () -> bookingService.createBooking(request(BookingType.FLIGHT)));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo("A1");
    }

    @Test
    void theSystemActorCannotNameAnAgentOfAnotherAgency() {
        BookingCreateRequest r = request(BookingType.FLIGHT);
        r.setAgentId("A9");
        assertThatThrownBy(() -> SystemActor.call("T1", () -> bookingService.createBooking(r)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found in this agency: A9");
    }

    @Test
    void anAgentCannotBookATypeTheyDoNotHandle() {
        ownerPicks(agent("A1", "Priya", List.of(ServiceType.VISA)));

        assertThatThrownBy(() -> bookingService.createBooking(request(BookingType.FLIGHT)))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("You don't handle FLIGHT");
    }

    @Test
    void anAgentMayBookATypeTheyHandle() {
        ownerPicks(agent("A1", "Priya", List.of(ServiceType.FLIGHT)));
        clientFound();
        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();

        assertThat(bookingService.createBooking(request(BookingType.FLIGHT)).getAgentId()).isEqualTo("A1");
    }

    @Test
    void anAgentWithNoRecordedServicesHandlesNoType() {
        ownerPicks(agent("A1", "Priya", null));

        assertThatThrownBy(() -> bookingService.createBooking(request(BookingType.HOTEL)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You don't handle HOTEL - only agents who do can create a HOTEL booking");
        verifyNoInteractions(clientCreditService);
    }

    // ---------------------------------------------------------------- log

    @Test
    void loggingABookingConfirmsItAndPricesItFromItsVoucher() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Air Co").build()));
        List<Booking> store = new ArrayList<>();
        when(bookingRepository.save(any())).thenAnswer(i -> { store.add(i.getArgument(0)); return i.getArgument(0); });
        when(bookingRepository.findById(anyString())).thenAnswer(i -> Optional.of(store.get(0)));
        when(supplierInvoiceService.draftForVoucher(any(), eq("V1"), isNull(), isNull(), eq(new BigDecimal("1000")),
                isNull(), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO))).thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI1").build());
        BookingCreateRequest r = request(BookingType.PACKAGE);
        r.setVendorId("V1");
        r.setPnr("PNR1");
        r.setNetCost(new BigDecimal("1000"));
        r.setSellingPrice(new BigDecimal("1200"));

        BookingResponse response = bookingService.logBooking(r, "D1", "files/v.pdf", "v.pdf", "application/pdf",
                BookingDocumentType.E_TICKET, null, null);

        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.getNetCost()).isEqualByComparingTo("1000");
        assertThat(response.getSellingPrice()).isEqualByComparingTo("1200");
        assertThat(response.getProfit()).isEqualByComparingTo("200");
        assertThat(response.getSupplier()).isEqualTo("Air Co");
        ArgumentCaptor<BookingDocument> voucher = ArgumentCaptor.forClass(BookingDocument.class);
        verify(bookingDocumentRepository).save(voucher.capture());
        assertThat(voucher.getValue().getId()).isEqualTo("D1");
        assertThat(voucher.getValue().getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(voucher.getValue().getMarkupValue()).isEqualByComparingTo("200");
        assertThat(voucher.getValue().getSupplierInvoiceId()).isEqualTo("SI1");
        assertThat(voucher.getValue().getCreatedBy()).isEqualTo("A1");
        assertThat(voucher.getValue().getPurchaseCurrency()).isNull();
        verify(clientCreditService).assertWithinLimit("C1", new BigDecimal("1200"), false);
    }

    @Test
    void aLoggedVoucherInAnotherCurrencyBillsTheSupplierInIt() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Air Co").build()));
        List<Booking> store = new ArrayList<>();
        when(bookingRepository.save(any())).thenAnswer(i -> { store.add(i.getArgument(0)); return i.getArgument(0); });
        when(bookingRepository.findById(anyString())).thenAnswer(i -> Optional.of(store.get(0)));
        when(supplierInvoiceService.draftForVoucher(any(), eq("V1"), eq("PNR1"), eq("USD"), eq(new BigDecimal("12")),
                eq(new BigDecimal("83")), any(), any())).thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI1").build());
        BookingCreateRequest r = request(BookingType.PACKAGE);
        r.setVendorId("V1");
        r.setNetCost(new BigDecimal("996"));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("10"));
        r.setOverrideCreditLimit(true);
        FxMath.Pricing pricing = new FxMath.Pricing(new BigDecimal("996"), new BigDecimal("10"),
                new FxMath.Converted("USD", new BigDecimal("12"), new BigDecimal("83"), new BigDecimal("996")), null);

        BookingResponse response = bookingService.logBooking(r, "D1", "k", "v.pdf", "application/pdf",
                BookingDocumentType.E_TICKET, "PNR1", pricing);

        assertThat(response.getSellingPrice()).isEqualByComparingTo("1096");
        assertThat(response.getPnr()).isEqualTo("PNR1");
        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        ArgumentCaptor<BookingDocument> voucher = ArgumentCaptor.forClass(BookingDocument.class);
        verify(bookingDocumentRepository).save(voucher.capture());
        assertThat(voucher.getValue().getMarkupMode()).isEqualTo(MarkupMode.PERCENT);
        assertThat(voucher.getValue().getPurchaseCurrency()).isEqualTo("USD");
        assertThat(voucher.getValue().getPurchasePrice()).isEqualByComparingTo("12");
        verify(clientCreditService).assertWithinLimit("C1", new BigDecimal("1096"), true);
    }

    @Test
    void loggingStopsWhenTheNewBookingCannotBeReadBack() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        BookingCreateRequest r = request(BookingType.PACKAGE);

        assertThatThrownBy(() -> bookingService.logBooking(r, "D1", "k", "v.pdf", "application/pdf",
                BookingDocumentType.E_TICKET, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Booking vanished mid-create");
        verify(bookingDocumentRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- lists

    @Test
    @SuppressWarnings("unchecked")
    void anAgentsListAppliesEveryFilterTheyGive() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", "Priya", List.of(ServiceType.FLIGHT))));
        when(bookingRepository.findAll(any(Specification.class))).thenReturn(List.of(booking().build()));

        List<BookingResponse> list = bookingService.listBookings(BookingType.FLIGHT, BookingStatus.CONFIRMED, "Goa", "A1", "V1");
        List<BookingResponse> blanks = bookingService.listBookings(null, null, " ", " ", " ");

        assertThat(list).extracting(BookingResponse::getId).containsExactly("B1");
        assertThat(blanks).extracting(BookingResponse::getBookingRef).containsExactly("BK00000042");
        verify(bookingRepository, times(2)).findAll(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTwoArgumentListReadsEveryBookingForAnOwner() {
        when(bookingRepository.findAll(any(Specification.class))).thenReturn(List.of());
        assertThat(bookingService.listBookings(null, null)).isEmpty();
        verifyNoInteractions(agentRepository);
    }

    @Test
    void anAgentWithoutARecordCannotList() {
        as("A1", UserType.AGENT);
        assertThatThrownBy(() -> bookingService.listBookings(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePagedListsKeepThePageAndSearch() {
        when(bookingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(i -> new PageImpl<>(List.of(booking().build()), i.getArgument(1), 1));

        PagedResponse<BookingResponse> plain = bookingService.listBookings(null, null, PageRequest.of(2, 5));
        bookingService.listBookings(null, null, "Goa", null, null, PageRequest.of(0, 10));
        bookingService.listBookings(null, null, null, null, null, "asha", null, PageRequest.of(0, 10));
        bookingService.listBookings(null, null, null, null, null, " ", null, true, PageRequest.of(0, 10));

        assertThat(plain.getContent()).extracting(BookingResponse::getId).containsExactly("B1");
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository, times(4)).findAll(any(Specification.class), pages.capture());
        assertThat(pages.getAllValues().get(0).getPageNumber()).isEqualTo(2);
        assertThat(pages.getAllValues().get(0).getPageSize()).isEqualTo(5);
        assertThat(pages.getAllValues().get(0).getSort()).isEqualTo(com.voyra.crm.util.BookingSort.parse(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unbilledBookingsAreScopedForAnAgentAndAgencyWideOtherwise() {
        when(bookingRepository.findAll(any(Specification.class))).thenReturn(List.of(booking().build()));
        assertThat(bookingService.listUnbilledBookings()).extracting(BookingResponse::getId).containsExactly("B1");
        verifyNoInteractions(agentRepository);

        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", "Priya", List.of())));
        assertThat(bookingService.listUnbilledBookings()).hasSize(1);
        verify(agentRepository).findById("A1");
    }

    @Test
    void anAgentWithoutARecordHasNoUnbilledList() {
        as("A1", UserType.AGENT);
        assertThatThrownBy(() -> bookingService.listUnbilledBookings())
                .isInstanceOf(IllegalStateException.class).hasMessage("Agent not found: A1");
    }

    @Test
    void changeAlertsNameTheirBookingAndLoadEachBookingOnce() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(accountingChangeAlertRepository.findByAcknowledgedFalseOrderByRaisedAtDesc()).thenReturn(List.of(
                AccountingChangeAlert.builder().id("AL1").bookingId("B1").fieldName("pnr").oldValue("X").newValue("Y")
                        .raisedAt(at).raisedBy("A1").acknowledged(false).build(),
                AccountingChangeAlert.builder().id("AL2").bookingId("B1").fieldName("netCost").build(),
                AccountingChangeAlert.builder().id("AL3").bookingId("GONE").fieldName("pnr").build()));
        found(booking().build());
        when(bookingRepository.findById("GONE")).thenReturn(Optional.empty());

        List<AccountingChangeAlertResponse> alerts = bookingService.listUnacknowledgedChangeAlerts();

        assertThat(alerts).extracting(AccountingChangeAlertResponse::getBookingLabel)
                .containsExactly("BK00000042 · Asha / Goa", "BK00000042 · Asha / Goa", null);
        assertThat(alerts.get(0).getOldValue()).isEqualTo("X");
        assertThat(alerts.get(0).getNewValue()).isEqualTo("Y");
        assertThat(alerts.get(0).getRaisedAt()).isEqualTo(at);
        verify(bookingRepository, times(1)).findById("B1");
    }

    @Test
    void acknowledgingAnAlertStampsWhoAndWhen() {
        AccountingChangeAlert alert = AccountingChangeAlert.builder().id("AL1").acknowledged(false).build();
        when(accountingChangeAlertRepository.findById("AL1")).thenReturn(Optional.of(alert));

        bookingService.acknowledgeChangeAlert("AL1");

        assertThat(alert.getAcknowledged()).isTrue();
        assertThat(alert.getAcknowledgedBy()).isEqualTo("O1");
        assertThat(alert.getAcknowledgedAt()).isNotNull();
        verify(accountingChangeAlertRepository).save(alert);
    }

    @Test
    void acknowledgingAnUnknownAlertIsRefused() {
        assertThatThrownBy(() -> bookingService.acknowledgeChangeAlert("AL9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Change alert not found: AL9");
    }

    @Test
    void aLeadsBookingsAreListedWithoutAnAccessCheck() {
        when(bookingRepository.findByLeadId("L1")).thenReturn(List.of(booking().build()));
        assertThat(bookingService.listForLead("L1")).extracting(BookingResponse::getId).containsExactly("B1");
    }

    // ---------------------------------------------------------------- update

    @Test
    void anEditCopiesEveryFieldGivenAndPointsAtTheNewSupplier() {
        Booking b = booking().vendorId("V1").supplier("Old Co").build();
        found(b);
        when(vendorRepository.findById("V2")).thenReturn(Optional.of(Vendor.builder().id("V2").name("New Co").build()));
        Invoice invoice = Invoice.builder().id("I1").build();
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of(invoice));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.REVENUE_RECOGNIZED))
                .thenReturn(Optional.of(JournalEntry.builder().id("J1").build()));
        LocalDate d = LocalDate.of(2026, 12, 1);
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setPnr("PNR2"); r.setTicketNo("T-1"); r.setAirline("AI"); r.setSupplier("Typed Co");
        r.setJourneyDate(d); r.setReturnDate(d.plusDays(5)); r.setTripType("ROUND_TRIP"); r.setNotes("note");
        r.setFlightNumber("AI101"); r.setFlightFrom("DEL"); r.setFlightTo("GOI"); r.setFlightCabin("Economy");
        r.setFlightDepartTime("09:00"); r.setFlightArriveTime("11:30"); r.setFlightArriveDate(d);
        r.setDestination("Goa North"); r.setVendorId("V2");
        r.setHotelConfirmationNo("H1"); r.setHotelName("Sea View"); r.setHotelCity("Panaji"); r.setHotelCountryCode("IN");
        r.setHotelCheckIn(d); r.setHotelCheckOut(d.plusDays(2)); r.setHotelRoomType("Deluxe"); r.setHotelBoardBasis("BB");
        r.setHotelRooms(1);
        r.setVisaApplicationNo("VA1"); r.setVisaCountry("AE"); r.setVisaAppliedDate(d); r.setVisaAppointmentDate(d); r.setVisaIssuedDate(d);
        r.setTransferVoucherNo("TV1"); r.setTransferVehicleType("Sedan"); r.setTransferPickup("Airport"); r.setTransferDropoff("Hotel");
        r.setTransferDate(d); r.setTransferTime("10:00");
        r.setInternationalTrip(true);
        r.setPassengers(List.of());

        BookingResponse response = bookingService.updateBooking("B1", r);

        assertThat(response.getPnr()).isEqualTo("PNR2");
        assertThat(b.getTicketNo()).isEqualTo("T-1");
        assertThat(b.getAirline()).isEqualTo("AI");
        assertThat(b.getVendorId()).isEqualTo("V2");
        assertThat(b.getSupplier()).isEqualTo("New Co");
        assertThat(b.getNotes()).isEqualTo("note");
        assertThat(b.getFlightArriveTime()).isEqualTo("11:30");
        assertThat(b.getDestination()).isEqualTo("Goa North");
        assertThat(b.getHotelBoardBasis()).isEqualTo("BB");
        assertThat(b.getHotelRooms()).isEqualTo(1);
        assertThat(b.getVisaIssuedDate()).isEqualTo(d);
        assertThat(b.getTransferTime()).isEqualTo("10:00");
        assertThat(b.getInternationalTrip()).isTrue();
        assertThat(b.getDepartureDate()).isEqualTo(d);
        assertThat(b.getUpdatedBy()).isEqualTo("O1");
        verify(bookingPassengerRepository).deleteByBookingId("B1");
        verify(journalService).reverse("J1", "Booking B1 departure rescheduled to " + d + ", after revenue was already recognized");
        verifyNoInteractions(accountingChangeAlertRepository);
    }

    @Test
    void theSupplierOfABookingWithVouchersIsChangedOnTheVoucher() {
        found(booking().vendorId("V1").build());
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(BookingDocument.builder().id("D1").build()));
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setVendorId("V2");

        assertThatThrownBy(() -> bookingService.updateBooking("B1", r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The supplier comes from this booking's vouchers - change it on its voucher");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void anUnknownSupplierIsRefusedOnEdit() {
        found(booking().build());
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setVendorId("V9");
        assertThatThrownBy(() -> bookingService.updateBooking("B1", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier not found: V9");
    }

    @Test
    void aBlankSupplierOrBlankDestinationLeavesTheBookingAsItIs() {
        Booking b = booking().vendorId("V1").supplier("Air Co").build();
        found(b);
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setVendorId(" ");
        r.setDestination(" ");
        r.setVendorId(" ");

        bookingService.updateBooking("B1", r);

        assertThat(b.getVendorId()).isEqualTo("V1");
        assertThat(b.getDestination()).isEqualTo("Goa");
        verifyNoInteractions(vendorRepository);
        verify(invoiceRepository, never()).findByBookingId(any());
    }

    @Test
    void editingAConfirmedBookingDraftsItsBillRaisesAlertsAndAnEarlierDateReversesNothing() {
        Booking b = booking().bookingStatus(BookingStatus.CONFIRMED).vendorId("V1").netCost(new BigDecimal("500"))
                .sellingPrice(new BigDecimal("600")).profit(new BigDecimal("100")).journeyDate(LocalDate.of(2026, 12, 20))
                .departureDate(LocalDate.of(2026, 12, 20)).build();
        found(b);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of());
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setJourneyDate(LocalDate.of(2026, 12, 10));

        bookingService.updateBooking("B1", r);

        verify(supplierInvoiceService).createAutoDraft(b, "V1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountingChangeAlert>> alerts = ArgumentCaptor.forClass(List.class);
        verify(accountingChangeAlertRepository).saveAll(alerts.capture());
        assertThat(alerts.getValue()).singleElement().satisfies(a -> {
            assertThat(a.getFieldName()).isEqualTo("journeyDate");
            assertThat(a.getBookingId()).isEqualTo("B1");
            assertThat(a.getRaisedBy()).isEqualTo("O1");
            assertThat(a.getAcknowledged()).isFalse();
        });
        verify(invoiceRepository, never()).findByBookingId(any());
    }

    @Test
    void anUnchangedConfirmedBookingRaisesNoAlertAndKeepsItsExistingBill() {
        Booking b = booking().bookingStatus(BookingStatus.CONFIRMED).vendorId("V1").netCost(new BigDecimal("500")).sellingPrice(new BigDecimal("500")).build();
        found(b);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(SupplierInvoice.builder().id("SI1").build()));

        bookingService.updateBooking("B1", new BookingUpdateRequest());

        verify(supplierInvoiceService, never()).createAutoDraft(any(), any());
        verify(accountingChangeAlertRepository, never()).saveAll(any());
    }

    @Test
    void aConfirmedBookingWithoutASupplierOrCostDraftsNoBill() {
        Booking noVendor = booking().bookingStatus(BookingStatus.CONFIRMED).netCost(new BigDecimal("500")).build();
        Booking blankVendor = booking().id("B2").bookingStatus(BookingStatus.CONFIRMED).vendorId(" ").netCost(new BigDecimal("500")).build();
        Booking noCost = booking().id("B3").bookingStatus(BookingStatus.CONFIRMED).vendorId("V1").netCost(null).build();
        Booking zeroCost = booking().id("B4").bookingStatus(BookingStatus.CONFIRMED).vendorId("V1").netCost(BigDecimal.ZERO).build();
        for (Booking b : List.of(noVendor, blankVendor, noCost, zeroCost)) {
            found(b);
            bookingService.updateBooking(b.getId(), new BookingUpdateRequest());
        }
        verifyNoInteractions(supplierInvoiceService, supplierInvoiceRepository);
    }

    // ---------------------------------------------------------------- status

    @Test
    void aCancelledBookingCannotBeReopened() {
        found(booking().bookingStatus(BookingStatus.CANCELLED).build());
        BookingStatusUpdateRequest r = new BookingStatusUpdateRequest();
        r.setBookingStatus(BookingStatus.PENDING);
        assertThatThrownBy(() -> bookingService.updateStatus("B1", r))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A cancelled booking can't be reopened - create a new booking instead");
    }

    @Test
    void reConfirmingAConfirmedBookingChecksNothingAndDraftsNoBill() {
        Booking b = booking().bookingStatus(BookingStatus.CONFIRMED).build();
        found(b);
        BookingStatusUpdateRequest r = new BookingStatusUpdateRequest();
        r.setBookingStatus(BookingStatus.CONFIRMED);

        assertThat(bookingService.updateStatus("B1", r).getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(bookingCostComponentRepository, supplierInvoiceService);
    }

    @Test
    void movingToPendingOrCompletedSkipsTheReadinessCheck() {
        Booking b = booking().build();
        found(b);
        BookingStatusUpdateRequest r = new BookingStatusUpdateRequest();
        r.setBookingStatus(BookingStatus.COMPLETED);

        assertThat(bookingService.updateStatus("B1", r).getBookingStatus()).isEqualTo(BookingStatus.COMPLETED);
        verifyNoInteractions(bookingCostComponentRepository);
    }

    @Test
    void aCompletedBookingConfirmedWithAPercentMarkupMustMatchItsComponents() {
        Booking b = booking().bookingStatus(BookingStatus.COMPLETED).markupMode(MarkupMode.PERCENT)
                .markupValue(new BigDecimal("10")).sellingPrice(new BigDecimal("44000.00")).build();
        found(b);
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("40000.00")).build()));
        BookingStatusUpdateRequest r = new BookingStatusUpdateRequest();
        r.setBookingStatus(BookingStatus.CONFIRMED);

        assertThat(bookingService.updateStatus("B1", r).getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoInteractions(supplierInvoiceService);
    }

    @Test
    void aBookingWithNoPriceOrMarkupFailsTheIntegrityCheckByTheComponentTotal() {
        Booking b = booking().bookingStatus(BookingStatus.COMPLETED).sellingPrice(null).markupValue(null).build();
        found(b);
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("100.00")).build()));
        BookingStatusUpdateRequest r = new BookingStatusUpdateRequest();
        r.setBookingStatus(BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> bookingService.updateStatus("B1", r))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Supplier net costs (100.00) plus markup (0) = 100.00, but the quoted selling price is 0 - a variance of 100.00");
    }

    @Test
    void cancellingALinkedBookingTellsItsLeadAndService() {
        Booking b = booking().leadId("L1").serviceId("S1").build();

        bookingService.markCancelled(b, "Client changed plans");

        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancelReason()).isEqualTo("Client changed plans");
        assertThat(b.getCancelledBy()).isEqualTo("O1");
        assertThat(b.getCancelledAt()).isNotNull();
        verify(bookingRepository).save(b);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_REMOVED, "Flight booking cancelled: Client changed plans");
        verify(serviceBookingStatusSync).onBookingRemoved("S1");
    }

    @Test
    void cancellingAStandaloneBookingTouchesNoLead() {
        Booking b = booking().build();
        bookingService.markCancelled(b, "x");
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(leadTimelineService, serviceBookingStatusSync);
    }

    @Test
    void thePublicResponseIsTheBookingsResponse() {
        assertThat(bookingService.toResponsePublic(booking().build()).getBookingRef()).isEqualTo("BK00000042");
    }

    @Test
    void deadlinesAreSetOnlyWhenGiven() {
        Booking b = booking().deadlineNote("keep").build();
        found(b);
        BookingDeadlineUpdateRequest none = new BookingDeadlineUpdateRequest();
        bookingService.updateDeadlines("B1", none);
        assertThat(b.getDeadlineNote()).isEqualTo("keep");

        BookingDeadlineUpdateRequest all = new BookingDeadlineUpdateRequest();
        LocalDate t = LocalDate.of(2026, 11, 1);
        all.setTicketingDeadline(t);
        all.setCancellationDeadline(t.plusDays(1));
        all.setDeadlineNote("call airline");
        BookingResponse response = bookingService.updateDeadlines("B1", all);

        assertThat(response.getTicketingDeadline()).isEqualTo(t);
        assertThat(response.getCancellationDeadline()).isEqualTo(t.plusDays(1));
        assertThat(response.getDeadlineNote()).isEqualTo("call airline");
        verify(bookingRepository, times(2)).save(b);
    }

    // ---------------------------------------------------------------- delete

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any())).thenAnswer(i -> i.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @Test
    void onlyAPendingBookingCanBeDeleted() {
        runTransactionsInline();
        found(booking().bookingStatus(BookingStatus.CONFIRMED).build());
        assertThatThrownBy(() -> bookingService.deleteBooking("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Only a pending booking can be deleted - cancel it instead");
    }

    @Test
    void eachKindOfAccountingRecordBlocksADelete() {
        runTransactionsInline();
        found(booking().build());
        when(invoiceRepository.existsByBookingId("B1")).thenReturn(true, false, false, false, false, false, false);
        when(paymentReceiptRepository.existsByBookingId("B1")).thenReturn(true, false, false, false, false, false);
        when(creditNoteRepository.existsByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(true, false, false, false, false);
        when(customerLedgerEntryRepository.existsByBookingId("B1")).thenReturn(true, false, false, false);
        when(feedbackRepository.existsByBookingId("B1")).thenReturn(true, false, false);
        when(supplierPaymentRepository.existsByBookingId("B1")).thenReturn(true, false);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.APPROVED).build()));

        for (int i = 0; i < 7; i++) {
            assertThatThrownBy(() -> bookingService.deleteBooking("B1"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("This booking has accounting records - cancel it instead");
        }
        verify(bookingRepository, never()).delete(any(Booking.class));
    }

    @Test
    void deletingALinkedBookingWithdrawsItsUnapprovedBillsAndTellsItsLead() {
        runTransactionsInline();
        Booking b = booking().leadId("L1").serviceId("S1").build();
        found(b);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED)).thenReturn(List.of(
                SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.DRAFT).build(),
                SupplierInvoice.builder().id("SI2").status(SupplierInvoiceStatus.PENDING_APPROVAL).build()));

        bookingService.deleteBooking("B1");

        verify(supplierInvoiceService).cancelForBooking("SI1", "Booking deleted");
        verify(supplierInvoiceService).cancelForBooking("SI2", "Booking deleted");
        verify(bookingDocumentRepository).deleteByBookingId("B1");
        verify(bookingPassengerRepository).deleteByBookingId("B1");
        verify(bookingRepository).delete(b);
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_REMOVED, "Flight booking removed");
        verify(serviceBookingStatusSync).onBookingRemoved("S1");
    }

    // ---------------------------------------------------------------- vouchers

    private static BookingDocument.BookingDocumentBuilder voucher() {
        return BookingDocument.builder().id("D1").bookingId("B1").createdAt(LocalDateTime.of(2026, 10, 1, 10, 0));
    }

    @Test
    void aVoucherWithoutCostOrMarkupAddsNothingButKeepsTheBookingsSupplier() {
        Booking b = booking().vendorId("V1").supplier("Air Co").netCost(BigDecimal.ZERO).build();
        BookingDocument v = voucher().netCost(null).build();

        bookingService.applyVoucher(b, v, " ", false);

        assertThat(v.getSellingPrice()).isEqualByComparingTo("0");
        assertThat(v.getVendorId()).isEqualTo("V1");
        assertThat(b.getNetCost()).isEqualByComparingTo("0");
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        verifyNoInteractions(supplierInvoiceService, vendorRepository, bookingAccountingSync);
        verify(bookingRepository).save(b);
    }

    @Test
    void aVoucherNeedsASupplier() {
        Booking b = booking().build();
        assertThatThrownBy(() -> bookingService.applyVoucher(b, voucher().build(), null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Pick the supplier of this voucher");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aSecondVoucherMustBeWithTheBookingsOneSupplier() {
        Booking b = booking().vendorId("V1").supplier("Air Co").build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher().id("D0").build()));

        assertThatThrownBy(() -> bookingService.applyVoucher(b, voucher().build(), "V2", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This booking is with Air Co - every voucher on a booking is with its one supplier");
    }

    @Test
    void commissionIsTakenOnFlightTicketsOnly() {
        Booking b = booking().type(BookingType.HOTEL).vendorId("V1").build();
        BookingDocument v = voucher().commission(new BigDecimal("50")).build();
        assertThatThrownBy(() -> bookingService.applyVoucher(b, v, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Commission is taken on flight tickets only");
    }

    @Test
    void aSecondVoucherOnAConfirmedInvoicedHotelAddsUpTakesTheReferenceAndBillsInItsCurrency() {
        Booking b = booking().type(BookingType.HOTEL).bookingStatus(BookingStatus.CONFIRMED).vendorId("V1").supplier("Stay Co")
                .netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1100")).commission(null)
                .paymentStatusSource(PaymentStatusSource.DERIVED).build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                voucher().id("D0").netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1100")).build()));
        BookingDocument v = voucher().netCost(new BigDecimal("830")).markupMode(MarkupMode.FLAT).markupValue(new BigDecimal("70"))
                .referenceNumber("  HCONF-9  ").purchaseCurrency("USD").purchasePrice(new BigDecimal("10")).purchaseRate(new BigDecimal("83")).build();
        when(supplierInvoiceService.draftForVoucher(b, "V1", "  HCONF-9  ", "USD", new BigDecimal("10"), new BigDecimal("83"),
                BigDecimal.ZERO, BigDecimal.ZERO)).thenReturn(com.voyra.crm.dto.SupplierInvoiceResponse.builder().id("SI2").build());

        bookingService.applyVoucher(b, v, "V1", true);

        assertThat(b.getNetCost()).isEqualByComparingTo("1830");
        assertThat(b.getSellingPrice()).isEqualByComparingTo("2000");
        assertThat(b.getProfit()).isEqualByComparingTo("170");
        assertThat(b.getMarkupMode()).isEqualTo(MarkupMode.FLAT);
        assertThat(b.getHotelConfirmationNo()).isEqualTo("HCONF-9");
        assertThat(v.getSupplierInvoiceId()).isEqualTo("SI2");
        verify(clientCreditService).assertWithinLimit("C1", new BigDecimal("900"), true);
        verify(accountingChangeAlertRepository).saveAll(any());
        verify(bookingAccountingSync).syncPayment("B1");
    }

    @Test
    void aVoucherReferenceIsCutToTheColumnAndFilledPerBookingType() {
        String longRef = "R".repeat(120);
        for (BookingType type : List.of(BookingType.VISA, BookingType.TRANSFER, BookingType.PACKAGE)) {
            Booking b = booking().type(type).vendorId("V1").build();
            bookingService.applyVoucher(b, voucher().referenceNumber(longRef).build(), null, false);
            String ref = switch (type) {
                case VISA -> b.getVisaApplicationNo();
                case TRANSFER -> b.getTransferVoucherNo();
                default -> b.getPnr();
            };
            assertThat(ref).hasSize(100);
        }
    }

    @Test
    void aVoucherWithABlankReferenceLeavesTheBookingWithoutOne() {
        Booking b = booking().vendorId("V1").build();
        bookingService.applyVoucher(b, voucher().referenceNumber(" ").build(), null, false);
        assertThat(b.getPnr()).isNull();
    }

    @Test
    void reversingAnUnpricedVoucherChangesNothing() {
        Booking b = booking().build();
        bookingService.reverseVoucher(b, voucher().netCost(null).sellingPrice(null).build());
        verifyNoInteractions(bookingRepository, supplierInvoiceRepository);
    }

    @Test
    void reversingAVoucherWhoseBillIsGoneOrCancelledJustTakesItsAmountsOff() {
        Booking b = booking().bookingStatus(BookingStatus.CONFIRMED).netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1500"))
                .commission(new BigDecimal("20")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.empty());
        when(supplierInvoiceRepository.findById("SI2")).thenReturn(Optional.of(SupplierInvoice.builder().id("SI2").status(SupplierInvoiceStatus.CANCELLED).build()));

        bookingService.reverseVoucher(b, voucher().supplierInvoiceId("SI1").netCost(new BigDecimal("400")).sellingPrice(new BigDecimal("500"))
                .commissionInr(new BigDecimal("20")).build());
        bookingService.reverseVoucher(b, voucher().supplierInvoiceId("SI2").netCost(new BigDecimal("100")).sellingPrice(null).build());

        assertThat(b.getNetCost()).isEqualByComparingTo("500");
        assertThat(b.getSellingPrice()).isEqualByComparingTo("1000");
        assertThat(b.getCommission()).isEqualByComparingTo("0");
        assertThat(b.getProfit()).isEqualByComparingTo("500");
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(supplierInvoiceService, never()).cancelForBooking(any(), any());
        verify(accountingChangeAlertRepository, times(2)).saveAll(any());
    }

    @Test
    void anApprovedBillLocksItsVoucher() {
        Booking b = booking().sellingPrice(new BigDecimal("500")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.APPROVED).build()));
        BookingDocument v = voucher().supplierInvoiceId("SI1").netCost(new BigDecimal("400")).build();

        assertThatThrownBy(() -> bookingService.reverseVoucher(b, v))
                .isInstanceOf(IllegalStateException.class).hasMessage("This voucher's supplier bill is already approved - it can't be removed");
        assertThatThrownBy(() -> bookingService.reverseVoucher(b, v, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This voucher's supplier bill is already approved - its cost, markup or supplier can't be changed");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aVoucherWhosePriceIsInvoicedCannotBeRemoved() {
        Booking b = booking().sellingPrice(new BigDecimal("500")).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().lineSubtotal(new BigDecimal("500")).build()));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.PENDING_APPROVAL).build()));

        assertThatThrownBy(() -> bookingService.reverseVoucher(b, voucher().supplierInvoiceId("SI1").sellingPrice(new BigDecimal("200")).build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This voucher's price is already invoiced - it can't be removed");
        verify(supplierInvoiceService).cancelForBooking("SI1", "Voucher removed");
    }

    @Test
    void removingTheLastPricedVoucherUnconfirmsTheBookingButAnEditDoesNot() {
        Booking removed = booking().bookingStatus(BookingStatus.CONFIRMED).sellingPrice(new BigDecimal("500")).netCost(new BigDecimal("400")).build();
        bookingService.reverseVoucher(removed, voucher().netCost(new BigDecimal("400")).sellingPrice(new BigDecimal("500")).build());
        assertThat(removed.getBookingStatus()).isEqualTo(BookingStatus.PENDING);

        Booking edited = booking().bookingStatus(BookingStatus.CONFIRMED).sellingPrice(new BigDecimal("500")).netCost(new BigDecimal("400")).build();
        bookingService.reverseVoucher(edited, voucher().netCost(new BigDecimal("400")).sellingPrice(new BigDecimal("500")).build(), false);
        assertThat(edited.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(edited.getSellingPrice()).isEqualByComparingTo("0");
        verify(invoiceRepository, times(1)).findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void anUnknownVoucherCannotBeRemoved() {
        found(booking().build());
        assertThatThrownBy(() -> bookingService.removeVoucher("B1", "D9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Document not found: D9");
    }

    @Test
    void anInvoicedVoucherOrOneOnADraftCannotChange() {
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED).build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().bookingDocumentId("D1").lineSubtotal(BigDecimal.TEN).build()));
        assertThatThrownBy(() -> bookingService.assertNotInvoiced(voucher().build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This voucher is already invoiced - it can't be changed or removed");

        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(Invoice.builder().id("DR1").bookingId("B1").build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("DR1"))
                .thenReturn(List.of(InvoiceLineItem.builder().bookingDocumentId("D2").build()));
        assertThatThrownBy(() -> bookingService.assertNotInvoiced(voucher().id("D2").build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This voucher is on a draft invoice - delete that draft first to change or remove it");
        assertThat(bookingService.draftVoucherIds("B1")).containsExactly("D2");
    }

    @Test
    void theReferenceFollowsThePricedVouchersPerBookingType() {
        BookingDocument unpriced = voucher().id("D0").referenceNumber("ANY-1").sellingPrice(BigDecimal.ZERO).createdAt(null).build();
        BookingDocument priced = voucher().referenceNumber(" TV-1 ").sellingPrice(new BigDecimal("300")).build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(unpriced, priced));
        Booking transfer = booking().type(BookingType.TRANSFER).sellingPrice(new BigDecimal("300")).build();

        bookingService.syncReference(transfer, null);

        assertThat(transfer.getTransferVoucherNo()).isEqualTo("TV-1");
        assertThat(transfer.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(transfer);
    }

    @Test
    void withNoPricedVoucherAnyVouchersReferenceIsUsed() {
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                voucher().referenceNumber("V-1").sellingPrice(null).build(), voucher().id("D2").referenceNumber(null).build()));
        Booking visa = booking().type(BookingType.VISA).build();

        bookingService.syncReference(visa, null);

        assertThat(visa.getVisaApplicationNo()).isEqualTo("V-1");
        assertThat(visa.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void aReferenceOnlyTheRemovedVoucherGaveIsClearedButATypedOneIsKept() {
        Booking hotel = booking().type(BookingType.HOTEL).hotelConfirmationNo("H-1, H-2").build();
        bookingService.syncReference(hotel, " H-2 ");
        assertThat(hotel.getHotelConfirmationNo()).isNull();

        Booking typed = booking().pnr("TYPED").build();
        bookingService.syncReference(typed, "OTHER");
        bookingService.syncReference(typed, " ");
        bookingService.syncReference(typed, null);
        Booking none = booking().build();
        bookingService.syncReference(none, "X");
        assertThat(typed.getPnr()).isEqualTo("TYPED");
        verify(bookingRepository, times(1)).save(any());
    }

    @Test
    void anUnchangedReferenceStillConfirmsABookingThatIsNowReady() {
        Booking b = booking().pnr("PNR1").sellingPrice(new BigDecimal("100")).build();
        bookingService.syncReference(b, null);
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(b);
    }

    @Test
    void aReferenceTooLongForTheColumnStillNamesTheFirstPnr() {
        String first = "A".repeat(120);
        assertThat(BookingService.joinReferences(List.of(first, "B")))
                .isEqualTo("A".repeat(90) + " +1 more");
        assertThat(BookingService.joinReferences(List.of("PNR1", "PNR2"))).isEqualTo("PNR1, PNR2");
    }

    // ---------------------------------------------------------------- commission

    @Test
    void anUnchangedCommissionDoesNothing() {
        Booking b = booking().build();
        bookingService.changeCommission(b, voucher().commission(BigDecimal.TEN).supplierTds(BigDecimal.ONE).build(),
                voucher().commission(new BigDecimal("10.00")).supplierTds(new BigDecimal("1.00")).build());
        verifyNoInteractions(bookingRepository, supplierInvoiceService);
    }

    @Test
    void aCancelledBookingsCommissionIsFixed() {
        Booking b = booking().bookingStatus(BookingStatus.CANCELLED).build();
        assertThatThrownBy(() -> bookingService.changeCommission(b, voucher().build(), voucher().supplierTds(BigDecimal.ONE).build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This booking is cancelled - its commission can't change");
    }

    @Test
    void commissionNeedsAFlightAndASupplierBill() {
        assertThatThrownBy(() -> bookingService.changeCommission(booking().type(BookingType.HOTEL).build(), voucher().build(),
                voucher().commission(BigDecimal.TEN).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Commission is taken on flight tickets only");
        assertThatThrownBy(() -> bookingService.changeCommission(booking().build(), voucher().build(),
                voucher().commission(BigDecimal.TEN).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This voucher has no supplier bill - it needs a supplier and a cost first");
    }

    @Test
    void aNewCommissionMovesTheBillAndTheProfit() {
        Booking b = booking().sellingPrice(new BigDecimal("1000")).netCost(new BigDecimal("900")).commission(new BigDecimal("30")).build();
        BookingDocument v = voucher().supplierInvoiceId("SI1").commission(new BigDecimal("30")).commissionInr(new BigDecimal("30")).build();
        BookingDocument priced = voucher().supplierInvoiceId("SI1").commission(new BigDecimal("50")).supplierTds(new BigDecimal("1"))
                .commissionInr(new BigDecimal("50")).build();

        bookingService.changeCommission(b, v, priced);

        verify(supplierInvoiceService).setDraftCommission("SI1", new BigDecimal("50"), new BigDecimal("1"));
        assertThat(b.getCommission()).isEqualByComparingTo("50");
        assertThat(b.getProfit()).isEqualByComparingTo("150");
        verify(bookingRepository).save(b);
    }

    @Test
    void droppingTheCommissionOfAVoucherWithoutABillOnlyMovesTheProfit() {
        Booking b = booking().sellingPrice(new BigDecimal("1000")).netCost(new BigDecimal("900")).commission(null).build();
        BookingDocument v = voucher().commission(new BigDecimal("30")).commissionInr(null).build();
        BookingDocument priced = voucher().commission(BigDecimal.ZERO).commissionInr(BigDecimal.ZERO).build();

        bookingService.changeCommission(b, v, priced);

        verifyNoInteractions(supplierInvoiceService);
        assertThat(b.getProfit()).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------- access and responses

    @Test
    void anUnknownBookingIsNotFound() {
        assertThatThrownBy(() -> bookingService.getBooking("B9"))
                .isInstanceOf(NotFoundException.class).hasMessage("Booking not found: B9");
    }

    @Test
    void anAgentSeesTheirOwnTheirServicesAndTheirTypesBookingsOnly() {
        as("A1", UserType.AGENT);
        found(booking().build());
        assertThat(bookingService.getBooking("B1").getId()).isEqualTo("B1");
        verify(agentRepository, never()).findById("A1");

        when(bookingRepository.findById("B2")).thenReturn(Optional.of(booking().id("B2").agentId("A2").serviceType(ServiceType.HOTEL).build()));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent("A1", "Priya", List.of(ServiceType.HOTEL))),
                Optional.empty());
        assertThat(bookingService.findAccessibleBooking("B2").getId()).isEqualTo("B2");
        assertThatThrownBy(() -> bookingService.findAccessibleBooking("B2"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This booking is not accessible to you");
    }

    @Test
    void theResponseShowsTheDraftTheLowMarginAndWhoGaveTheWork() {
        Booking b = booking().leadId("L1").netCost(new BigDecimal("980")).sellingPrice(new BigDecimal("1000")).build();
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(Invoice.builder().id("DR1").bookingId("B1").build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("DR1")).thenReturn(List.of(
                InvoiceLineItem.builder().bookingDocumentId("D1").build(), InvoiceLineItem.builder().build()));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(
                voucher().fileKey("k").supplierInvoiceId("SI1").build(), voucher().id("D2").build()));
        when(supplierInvoiceRepository.findAllById(List.of("SI1"))).thenReturn(List.of(
                SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.APPROVED).build()));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdByName("Dana").build()));
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());

        BookingResponse r = bookingService.toResponsePublic(b);

        assertThat(r.getDraftInvoiceId()).isEqualTo("DR1");
        assertThat(r.getAssignedByName()).isEqualTo("Dana");
        assertThat(r.getLowMargin()).isTrue();
        assertThat(r.getUninvoicedPrice()).isEqualByComparingTo("1000");
        assertThat(r.getDocuments()).extracting(d -> d.getOnDraft(), d -> d.getBillApproved(), d -> d.isHasFile())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, true, true),
                        org.assertj.core.groups.Tuple.tuple(false, false, false));
    }

    @Test
    void anAgencysOwnMarginThresholdDecidesLowMargin() {
        Booking b = booking().netCost(new BigDecimal("980")).sellingPrice(new BigDecimal("1000")).build();
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").lowMarginThresholdPercent(BigDecimal.ONE).build()));
        assertThat(bookingService.toResponsePublic(b).getLowMargin()).isFalse();
    }

    @Test
    void anAgencyWithoutAThresholdUsesTheDefault() {
        Booking b = booking().netCost(new BigDecimal("950")).sellingPrice(new BigDecimal("1000")).build();
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").build()));
        assertThat(bookingService.toResponsePublic(b).getLowMargin()).isTrue();
    }

    @Test
    void aCancelledBookingHasNothingLeftToInvoice() {
        Booking b = booking().bookingStatus(BookingStatus.CANCELLED).build();
        assertThat(bookingService.toResponsePublic(b).getUninvoicedPrice()).isEqualByComparingTo("0");
    }

    @Test
    void whoGaveTheWorkFallsBackToTheLeadsCreatorThenTheBookingsCreator() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdByName(" ").createdBy("A2").build()));
        when(leadRepository.findById("L2")).thenReturn(Optional.empty());
        when(agentRepository.findById("A2")).thenReturn(Optional.of(agent("A2", "Vikram", List.of())));
        when(agentRepository.findById("T1")).thenReturn(Optional.empty());
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").ownerName("Dana Owner").build()));

        assertThat(bookingService.toResponsePublic(booking().leadId("L1").build()).getAssignedByName()).isEqualTo("Vikram");
        assertThat(bookingService.toResponsePublic(booking().leadId("L2").createdBy("T1").build()).getAssignedByName()).isEqualTo("Dana Owner");
    }

    @Test
    void anAccountantRemarkIsTrimmedAndBlankClearsIt() {
        Booking b = booking().build();
        found(b);
        assertThat(bookingService.updateAccountantRemark("B1", "  check refund  ").getAccountantRemark()).isEqualTo("check refund");
        assertThat(bookingService.updateAccountantRemark("B1", " ").getAccountantRemark()).isNull();
        bookingService.updateAccountantRemark("B1", null);
        assertThat(b.getAccountantRemark()).isNull();
        verify(auditService, times(3)).recordUpdate(eq(com.voyra.crm.enums.AuditEntityType.BOOKING), eq("B1"), any(), any());
    }

    // ---------------------------------------------------------------- cost components

    @Test
    void costComponentsAreListedInOrder() {
        found(booking().build());
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().id("CC1").vendorId("V1").vendorName("Air Co").description("Fare")
                        .netCost(new BigDecimal("10")).currencyCode("USD").fxRateToInr(new BigDecimal("83"))
                        .netCostInr(new BigDecimal("830")).sortOrder(0).build()));

        List<BookingCostComponentResponse> list = bookingService.listCostComponents("B1");

        assertThat(list).singleElement().satisfies(c -> {
            assertThat(c.getVendorName()).isEqualTo("Air Co");
            assertThat(c.getNetCostInr()).isEqualByComparingTo("830");
            assertThat(c.getCurrencyCode()).isEqualTo("USD");
        });
    }

    @Test
    void foreignComponentsAreConvertedAndNameTheirSupplier() {
        Booking b = booking().sellingPrice(new BigDecimal("2000")).build();
        found(b);
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Air Co").build()));
        when(vendorRepository.findById("V9")).thenReturn(Optional.empty());
        BookingCostComponentRequest usd = new BookingCostComponentRequest();
        usd.setVendorId("V1");
        usd.setNetCost(new BigDecimal("10"));
        usd.setCurrencyCode("USD");
        usd.setFxRateToInr(new BigDecimal("83"));
        BookingCostComponentRequest unknown = new BookingCostComponentRequest();
        unknown.setVendorId("V9");
        BookingCostComponentRequest blank = new BookingCostComponentRequest();
        blank.setVendorId(" ");
        blank.setNetCost(new BigDecimal("100"));

        bookingService.replaceCostComponents("B1", List.of(usd, unknown, blank));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingCostComponent>> saved = ArgumentCaptor.forClass(List.class);
        verify(bookingCostComponentRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(BookingCostComponent::getVendorName, BookingCostComponent::getNetCostInr,
                        BookingCostComponent::getSortOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Air Co", new BigDecimal("830.00"), 0),
                        org.assertj.core.groups.Tuple.tuple(null, new BigDecimal("0.00"), 1),
                        org.assertj.core.groups.Tuple.tuple(null, new BigDecimal("100.00"), 2));
        assertThat(b.getNetCost()).isEqualByComparingTo("930");
        assertThat(b.getProfit()).isEqualByComparingTo("1070");
        verifyNoInteractions(accountingChangeAlertRepository);
    }

    @Test
    void clearingTheComponentsKeepsTheBookingsCost() {
        Booking b = booking().netCost(new BigDecimal("700")).build();
        found(b);

        assertThat(bookingService.replaceCostComponents("B1", List.of())).isEmpty();

        verify(bookingCostComponentRepository).deleteByBookingId("B1");
        assertThat(b.getNetCost()).isEqualByComparingTo("700");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void onlyBillsPastApprovalAreApproved() {
        assertThat(bookingService.approvedBillIds(List.of(voucher().build()))).isEmpty();
        when(supplierInvoiceRepository.findAllById(List.of("S1", "S2", "S3", "S4"))).thenReturn(List.of(
                SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.DRAFT).build(),
                SupplierInvoice.builder().id("S2").status(SupplierInvoiceStatus.PENDING_APPROVAL).build(),
                SupplierInvoice.builder().id("S3").status(SupplierInvoiceStatus.CANCELLED).build(),
                SupplierInvoice.builder().id("S4").status(SupplierInvoiceStatus.PAID).build()));
        assertThat(bookingService.approvedBillIds(List.of(voucher().supplierInvoiceId("S1").build(), voucher().supplierInvoiceId("S2").build(),
                voucher().supplierInvoiceId("S3").build(), voucher().supplierInvoiceId("S4").build()))).isEqualTo(Set.of("S4"));
    }

    // ---------------------------------------------------------------- remaining branches

    @Test
    void removingAVoucherTakesItsAmountsOffAndReturnsItsFile() {
        Booking b = booking().bookingStatus(BookingStatus.CONFIRMED).pnr("PNR1").netCost(new BigDecimal("400"))
                .sellingPrice(new BigDecimal("500")).build();
        found(b);
        BookingDocument v = voucher().referenceNumber("PNR1").fileKey("files/v1.pdf").netCost(new BigDecimal("400"))
                .sellingPrice(new BigDecimal("500")).build();
        when(bookingDocumentRepository.findByIdAndBookingId("D1", "B1")).thenReturn(Optional.of(v));

        assertThat(bookingService.removeVoucher("B1", "D1")).isEqualTo("files/v1.pdf");

        verify(bookingRepository).findByIdForUpdate("B1");
        verify(bookingDocumentRepository).delete(v);
        assertThat(b.getSellingPrice()).isEqualByComparingTo("0");
        assertThat(b.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.getPnr()).isNull();
    }

    @Test
    void anEditOfABookingWithoutPricesKeepsProfitAtZero() {
        Booking b = booking().bookingStatus(BookingStatus.CANCELLED).vendorId("V1").sellingPrice(null).netCost(null).commission(null).build();
        found(b);
        BookingUpdateRequest r = new BookingUpdateRequest();
        r.setVendorId("V1");

        BookingResponse response = bookingService.updateBooking("B1", r);

        assertThat(b.getProfit()).isEqualByComparingTo("0");
        assertThat(response.getLowMargin()).isFalse();
        verifyNoInteractions(vendorRepository);
    }

    @Test
    void anUnassignedServiceStaysUnassigned() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        LeadService visa = LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).label("Dubai visa").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(visa));
        BookingCreateRequest r = request(BookingType.VISA);
        r.setServiceId("S1");

        BookingResponse response = bookingService.createBooking(r);

        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.BOOKING_LOGGED, "Visa booking logged");
        verify(bookingRepository, times(1)).save(any());
        assertThat(response.getServiceAgentId()).isNull();
        assertThat(response.getServiceLabel()).isEqualTo("Dubai visa");
    }

    @Test
    void aBlankTypedSupplierFallsBackToTheVendorsNameAndABlankLeadIsIgnored() {
        ownerPicks(agent("A1", "Priya", List.of()));
        clientFound();
        when(vendorRepository.findById("V1")).thenReturn(Optional.empty());
        BookingCreateRequest r = request(BookingType.PACKAGE);
        r.setSupplier(" ");
        r.setVendorId("V1");

        assertThat(bookingService.createBooking(r).getSupplier()).isNull();
        r.setVendorId(" ");
        r.setLeadId(" ");
        BookingResponse blank = bookingService.createBooking(r);
        assertThat(blank.getSupplier()).isNull();
        assertThat(blank.getLeadId()).isNull();
        verifyNoInteractions(leadRepository);
        verify(vendorRepository, times(1)).findById(any());
    }

    @Test
    void aSecondVoucherOnABookingWithoutASupplierSetsNone() {
        Booking b = booking().build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher().id("D0").build()));

        bookingService.applyVoucher(b, voucher().build(), "V1", false);

        assertThat(b.getVendorId()).isNull();
        verifyNoInteractions(vendorRepository);
    }

    @Test
    void aConfirmedBookingsReferenceFollowsItsVoucherWithoutChangingStatus() {
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher().referenceNumber("NEW").build()));
        Booking confirmed = booking().bookingStatus(BookingStatus.CONFIRMED).pnr("OLD").build();
        bookingService.syncReference(confirmed, null);
        assertThat(confirmed.getPnr()).isEqualTo("NEW");
        assertThat(confirmed.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);

        Booking same = booking().bookingStatus(BookingStatus.CONFIRMED).pnr("NEW").build();
        bookingService.syncReference(same, null);
        Booking unpriced = booking().pnr("NEW").sellingPrice(null).build();
        bookingService.syncReference(unpriced, null);
        assertThat(unpriced.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        verify(bookingRepository, times(1)).save(any());
    }

    @Test
    void aVoucherWhoseBillIsStillADraftIsNotBillApproved() {
        Booking b = booking().build();
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(voucher().supplierInvoiceId("SI1").build()));
        when(supplierInvoiceRepository.findAllById(List.of("SI1"))).thenReturn(List.of(
                SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.DRAFT).build()));

        assertThat(bookingService.toResponsePublic(b).getDocuments()).singleElement()
                .satisfies(d -> assertThat(d.getBillApproved()).isFalse());
    }
}
