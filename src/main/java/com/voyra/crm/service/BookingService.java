package com.voyra.crm.service;

import com.voyra.crm.dto.AccountingChangeAlertResponse;
import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.BookingCostComponentRequest;
import com.voyra.crm.dto.BookingCostComponentResponse;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingDeadlineUpdateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.dto.BookingPassengerRequest;
import com.voyra.crm.dto.BookingPassengerResponse;
import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingSectorRequest;
import com.voyra.crm.dto.BookingSectorResponse;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.AccountingChangeAlert;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.ServiceType;
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
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.repository.spec.BookingSpecifications;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.BookingAccessChecker;
import com.voyra.crm.util.MarginCalculator;
import com.voyra.crm.util.ServiceBookingTypeMapper;
import com.voyra.crm.util.TripRules;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    /** The audited surface of a booking - read this array to know exactly what history records.
     *  Lead/service linkage and type are deliberately excluded - they never change after create. */
    private static final String[] AUDITED = {
            "pnr", "ticketNo", "airline", "supplier", "journeyDate", "returnDate", "tripType", "notes",
            "flightNumber", "flightFrom", "flightTo", "flightCabin", "flightDepartTime", "flightArriveTime", "flightArriveDate",
            "destination", "vendorId",
            "hotelConfirmationNo", "hotelName", "hotelCity", "hotelCountryCode", "hotelCheckIn", "hotelCheckOut",
            "hotelRoomType", "hotelBoardBasis", "hotelRooms",
            "visaApplicationNo", "visaCountry", "visaAppliedDate", "visaAppointmentDate", "visaIssuedDate",
            "transferVoucherNo", "transferVehicleType", "transferPickup", "transferDropoff",
            "transferDate", "transferTime",
            "netCost", "sellingPrice", "profit", "bookingStatus", "paymentStatus", "cancelReason",
            "refundState", "refundAmount", "refundDueDate",
            "ticketingDeadline", "cancellationDeadline", "deadlineNote"
    };

    private final BookingRepository bookingRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final BookingSectorRepository bookingSectorRepository;
    private final BookingCostComponentRepository bookingCostComponentRepository;
    private final AccountingChangeAlertRepository accountingChangeAlertRepository;
    private final VendorRepository vendorRepository;
    private final ClientRepository clientRepository;
    private final AgentRepository agentRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadRepository leadRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final com.voyra.crm.repository.InvoiceLineItemRepository invoiceLineItemRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final CustomerLedgerEntryRepository customerLedgerEntryRepository;
    private final FeedbackRepository feedbackRepository;
    private final AuditService auditService;
    private final LeadTimelineService leadTimelineService;
    private final ServiceInstanceService serviceInstanceService;
    private final ServiceBookingStatusSync serviceBookingStatusSync;
    private final SupplierInvoiceService supplierInvoiceService;
    private final ClientCreditService clientCreditService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final com.voyra.crm.repository.SupplierInvoiceRepository supplierInvoiceRepository;
    private final com.voyra.crm.repository.SupplierPaymentRepository supplierPaymentRepository;
    private final TenantRepository tenantRepository;
    private final JournalService journalService;
    private final com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;

    /** FRD US-ACC-6.1's example rate, used whenever an agency hasn't set its own in Settings. */
    private static final BigDecimal DEFAULT_LOW_MARGIN_THRESHOLD_PERCENT = BigDecimal.valueOf(8);

    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        // A pending booking is saved before it is booked with the supplier - the fare comes later.
        if (request.getNetCost() == null) request.setNetCost(BigDecimal.ZERO);
        if (request.getSellingPrice() == null) request.setSellingPrice(BigDecimal.ZERO);
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());
        if (request.getServiceId() == null || request.getServiceId().isBlank()) {
            assertAgentHandlesStandaloneType(request.getType(), owner.id());
        }
        clientCreditService.assertWithinLimit(request.getClientId(), request.getSellingPrice(),
                Boolean.TRUE.equals(request.getOverrideCreditLimit()));
        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + request.getClientId()));

        LeadService service = null;
        if (request.getServiceId() != null && !request.getServiceId().isBlank()) {
            service = leadServiceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()));
            // Same gate every other service mutation goes through - an agent who may log a
            // booking on this service is exactly an agent who may edit it.
            serviceInstanceService.assertEditAccess(service);
            BookingType expected = ServiceBookingTypeMapper.forService(service.getType());
            if (request.getType() != expected) {
                throw new IllegalArgumentException(
                        "type must be " + expected + " for a booking on a " + service.getType() + " service");
            }
        }
        // A standalone booking (no serviceId) can still name the lead it came from - the
        // only path this could previously never reach, which is what left an invoice's
        // lead traceability broken for every booking made from the Bookings tab rather than
        // a lead's own Service tab.
        String standaloneLeadId = null;
        if (service == null && request.getLeadId() != null && !request.getLeadId().isBlank()) {
            standaloneLeadId = leadRepository.findById(request.getLeadId())
                    .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + request.getLeadId()))
                    .getId();
        }

        Booking.BookingBuilder builder = Booking.builder()
                .id(generateUniqueBookingId())
                .bookingNumber(bookingRepository.nextBookingNumber())
                .clientId(client.getId())
                .clientName(client.getName())
                .agentId(owner.id())
                .agentName(owner.name())
                .type(request.getType())
                .destination(request.getDestination())
                .pnr(request.getPnr())
                .ticketNo(request.getTicketNo())
                .airline(request.getAirline())
                .supplier(supplierName(request.getSupplier(), request.getVendorId()))
                .vendorId(request.getVendorId())
                .journeyDate(request.getJourneyDate())
                .returnDate(request.getReturnDate())
                .tripType(request.getTripType())
                .internationalTrip(Boolean.TRUE.equals(request.getInternationalTrip()))
                .notes(request.getNotes())
                .flightNumber(request.getFlightNumber())
                .flightFrom(request.getFlightFrom())
                .flightTo(request.getFlightTo())
                .flightCabin(request.getFlightCabin())
                .flightDepartTime(request.getFlightDepartTime())
                .flightArriveTime(request.getFlightArriveTime())
                .flightArriveDate(request.getFlightArriveDate())
                .hotelConfirmationNo(request.getHotelConfirmationNo())
                .hotelName(request.getHotelName())
                .hotelCity(request.getHotelCity())
                .hotelCountryCode(request.getHotelCountryCode())
                .hotelCheckIn(request.getHotelCheckIn())
                .hotelCheckOut(request.getHotelCheckOut())
                .hotelRoomType(request.getHotelRoomType())
                .hotelBoardBasis(request.getHotelBoardBasis())
                .hotelRooms(request.getHotelRooms())
                .visaApplicationNo(request.getVisaApplicationNo())
                .visaCountry(request.getVisaCountry())
                .visaAppliedDate(request.getVisaAppliedDate())
                .visaAppointmentDate(request.getVisaAppointmentDate())
                .visaIssuedDate(request.getVisaIssuedDate())
                .transferVoucherNo(request.getTransferVoucherNo())
                .transferVehicleType(request.getTransferVehicleType())
                .transferPickup(request.getTransferPickup())
                .transferDropoff(request.getTransferDropoff())
                .transferDate(request.getTransferDate())
                .transferTime(request.getTransferTime())
                .netCost(request.getNetCost())
                .sellingPrice(request.getSellingPrice())
                .markupMode(request.getMarkupMode())
                .markupValue(request.getMarkupValue())
                .profit(computeProfit(request.getSellingPrice(), request.getNetCost()))
                .bookingStatus(BookingStatus.PENDING)
                .paymentStatus(request.getPaymentStatus() != null ? request.getPaymentStatus()
                        : com.voyra.crm.enums.PaymentStatus.PENDING)
                .bookingDate(LocalDate.now())
                .createdDate(LocalDateTime.now())
                .createdBy(owner.id());

        if (service != null) {
            // Snapshotted from the service, never trusted from the request - leadId/serviceLabel/
            // serviceAgent* must always agree with the service actually loaded above.
            builder.leadId(service.getLeadId())
                    .serviceId(service.getId())
                    .serviceType(service.getType())
                    .serviceLabel(service.getLabel())
                    .serviceAgentId(service.getAssignedAgentId())
                    .serviceAgentName(service.getAssignedAgentName());
        } else if (standaloneLeadId != null) {
            builder.leadId(standaloneLeadId);
        }

        Booking booking = builder.build();
        checkTrip(booking);
        booking.setDepartureDate(deriveDepartureDate(booking));
        bookingRepository.save(booking);
        if (request.getPassengers() != null) {
            replacePassengers(booking.getId(), request.getPassengers());
        }
        auditService.recordCreate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking));

        if (service != null) {
            leadTimelineService.record(service.getLeadId(), service.getId(), LeadTimelineEventType.BOOKING_LOGGED,
                    labelOrType(booking) + " booking logged" + (booking.getPnr() != null ? " (" + booking.getPnr() + ")" : ""));
            serviceBookingStatusSync.onBookingLogged(service.getId(), owner.id(), owner.name());
        }

        log.info("Booking created: bookingId={}, agentId={}, serviceId={}", booking.getId(), owner.id(), request.getServiceId());
        return toResponse(booking);
    }

    /**
     * The "log a booking" path: the supplier has already confirmed (the caller holds a reference
     * and the voucher), so the booking is created CONFIRMED with its voucher document in the same
     * transaction. The voucher file itself is stored by the caller before this runs - see
     * {@link BookingLogService}.
     */
    @Transactional
    public BookingResponse logBooking(BookingCreateRequest request, String documentId, String fileKey,
                                      String fileName, String contentType, BookingDocumentType docType,
                                      String reference) {
        Booking booking = bookingRepository.findById(createBooking(request).getId())
                .orElseThrow(() -> new IllegalStateException("Booking vanished mid-create"));
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        draftSupplierBillOnce(booking);
        bookingDocumentRepository.save(BookingDocument.builder()
                .id(documentId)
                .bookingId(booking.getId())
                .docType(docType)
                .referenceNumber(reference)
                .fileKey(fileKey)
                .fileName(fileName)
                .contentType(contentType)
                .createdAt(LocalDateTime.now())
                .createdBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build());
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter) {
        return listBookings(typeFilter, statusFilter, null, null, null);
    }

    /** FRD US-ACC-6.1's profitability filters - destination, agent, vendor. "Package Category" is
     *  covered by {@code typeFilter}; the booking has no separate category field. */
    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter,
            String destinationFilter, String agentIdFilter, String vendorIdFilter) {
        return bookingRepository.findAll(
                        scopeSpecification(typeFilter, statusFilter, destinationFilter, agentIdFilter, vendorIdFilter))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter, Pageable pageable) {
        return listBookings(typeFilter, statusFilter, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter,
            String destinationFilter, String agentIdFilter, String vendorIdFilter, Pageable pageable) {
        return listBookings(typeFilter, statusFilter, destinationFilter, agentIdFilter, vendorIdFilter, null, null, pageable);
    }

    /** The Bookings screen: free-text search (booking no., client, destination, reference) over the caller's scope. */
    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter,
            String destinationFilter, String agentIdFilter, String vendorIdFilter, String query, String sort, Pageable pageable) {
        return listBookings(typeFilter, statusFilter, destinationFilter, agentIdFilter, vendorIdFilter, query, sort, false, pageable);
    }

    /** As above; {@code moneyPending} narrows to bookings with client money or a refund still open - the accountant's worklist. */
    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter,
            String destinationFilter, String agentIdFilter, String vendorIdFilter, String query, String sort,
            boolean moneyPending, Pageable pageable) {
        pageable = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                com.voyra.crm.util.BookingSort.parse(sort));
        Specification<Booking> spec = scopeSpecification(typeFilter, statusFilter, destinationFilter, agentIdFilter, vendorIdFilter);
        if (query != null && !query.isBlank()) {
            spec = spec.and(BookingSpecifications.searchMatches(query));
        }
        if (moneyPending) {
            spec = spec.and(BookingSpecifications.moneyPending());
        }
        Page<Booking> page = bookingRepository.findAll(spec, pageable);
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * FRD US-ACC-1.1's "Unbilled Bookings" workbench - CONFIRMED bookings with no live invoice,
     * computed server-side (see {@code BookingSpecifications#confirmedAndUnbilled}) rather than
     * the previous approach of loading every non-cancelled booking into the browser and deriving
     * state there. Same agent/owner/accountant scoping as {@link #listBookings}.
     */
    @Transactional(readOnly = true)
    public List<BookingResponse> listUnbilledBookings() {
        List<Specification<Booking>> predicates = new ArrayList<>();
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            predicates.add(BookingSpecifications.accessibleToAgent(principal.userId(), agent.getManageableServices()));
        }
        predicates.add(BookingSpecifications.confirmedAndUnbilled());
        return bookingRepository.findAll(Specification.allOf(predicates)).stream().map(this::toResponse).toList();
    }

    /** Owner/Accountant work queue - see {@link #raiseChangeAlerts}. Not agent-scoped: payables
     *  and accounting review surfaces are agency-wide, matching SupplierInvoiceController's gate. */
    @Transactional(readOnly = true)
    public List<AccountingChangeAlertResponse> listUnacknowledgedChangeAlerts() {
        Map<String, Booking> bookingsById = new java.util.HashMap<>();
        return accountingChangeAlertRepository.findByAcknowledgedFalseOrderByRaisedAtDesc().stream()
                .map(a -> {
                    Booking b = bookingsById.computeIfAbsent(a.getBookingId(),
                            bid -> bookingRepository.findById(bid).orElse(null));
                    return AccountingChangeAlertResponse.builder()
                            .id(a.getId()).bookingId(a.getBookingId())
                            .bookingLabel(b != null ? labelFor(b) : null)
                            .fieldName(a.getFieldName()).oldValue(a.getOldValue()).newValue(a.getNewValue())
                            .raisedAt(a.getRaisedAt()).raisedBy(a.getRaisedBy())
                            .acknowledged(a.getAcknowledged())
                            .acknowledgedAt(a.getAcknowledgedAt()).acknowledgedBy(a.getAcknowledgedBy())
                            .build();
                })
                .toList();
    }

    @Transactional
    public void acknowledgeChangeAlert(String id) {
        AccountingChangeAlert alert = accountingChangeAlertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Change alert not found: " + id));
        alert.setAcknowledged(true);
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setAcknowledgedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        accountingChangeAlertRepository.save(alert);
    }

    /**
     * Built as a {@code Specification} rather than a chain of derived finders - see
     * {@code BookingSpecifications}'s javadoc. Exactly two queries total per call: one
     * {@code agentRepository.findById} to read {@code manageableServices} (agent callers only),
     * one {@code findAll(spec)} - independent of how many bookings exist.
     */
    private Specification<Booking> scopeSpecification(BookingType typeFilter, BookingStatus statusFilter,
            String destinationFilter, String agentIdFilter, String vendorIdFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Specification<Booking>> predicates = new ArrayList<>();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            predicates.add(BookingSpecifications.accessibleToAgent(principal.userId(), agent.getManageableServices()));
        }
        if (typeFilter != null) {
            predicates.add(BookingSpecifications.typeIs(typeFilter));
        }
        if (statusFilter != null) {
            predicates.add(BookingSpecifications.statusIs(statusFilter));
        }
        if (destinationFilter != null && !destinationFilter.isBlank()) {
            predicates.add(BookingSpecifications.destinationContains(destinationFilter));
        }
        if (agentIdFilter != null && !agentIdFilter.isBlank()) {
            predicates.add(BookingSpecifications.agentIs(agentIdFilter));
        }
        if (vendorIdFilter != null && !vendorIdFilter.isBlank()) {
            predicates.add(BookingSpecifications.vendorIs(vendorIdFilter));
        }
        return Specification.allOf(predicates);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String id) {
        return toResponse(findAccessibleBooking(id));
    }

    /** Every booking on a lead, across every service - feeds LeadDetailResponse.bookings so the
     *  Services tab renders them with no extra round trip. Not access-checked here; the lead
     *  itself is already access-checked by the caller (LeadService.getLeadDetail). */
    @Transactional(readOnly = true)
    public List<BookingResponse> listForLead(String leadId) {
        return bookingRepository.findByLeadId(leadId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public BookingResponse updateBooking(String id, BookingUpdateRequest request) {
        // Locked first: a supplier picked on a confirmed booking drafts its bill, once.
        bookingRepository.findByIdForUpdate(id);
        Booking booking = findAccessibleBooking(id);
        boolean alreadyConfirmed = booking.getBookingStatus() == BookingStatus.CONFIRMED;
        LocalDate oldDepartureDate = booking.getDepartureDate();
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);

        if (request.getPnr() != null) booking.setPnr(request.getPnr());
        if (request.getTicketNo() != null) booking.setTicketNo(request.getTicketNo());
        if (request.getAirline() != null) booking.setAirline(request.getAirline());
        if (request.getSupplier() != null) booking.setSupplier(request.getSupplier());
        if (request.getJourneyDate() != null) booking.setJourneyDate(request.getJourneyDate());
        if (request.getReturnDate() != null) booking.setReturnDate(request.getReturnDate());
        if (request.getTripType() != null) booking.setTripType(request.getTripType());
        if (request.getNotes() != null) booking.setNotes(request.getNotes().isBlank() ? null : request.getNotes());
        if (request.getFlightNumber() != null) booking.setFlightNumber(request.getFlightNumber());
        if (request.getFlightFrom() != null) booking.setFlightFrom(request.getFlightFrom());
        if (request.getFlightTo() != null) booking.setFlightTo(request.getFlightTo());
        if (request.getFlightCabin() != null) booking.setFlightCabin(request.getFlightCabin());
        if (request.getFlightDepartTime() != null) booking.setFlightDepartTime(request.getFlightDepartTime());
        if (request.getFlightArriveTime() != null) booking.setFlightArriveTime(request.getFlightArriveTime());
        if (request.getFlightArriveDate() != null) booking.setFlightArriveDate(request.getFlightArriveDate());
        if (request.getDestination() != null && !request.getDestination().isBlank()) booking.setDestination(request.getDestination());
        if (request.getVendorId() != null && !request.getVendorId().equals(booking.getVendorId())) setVendor(booking, request.getVendorId());
        if (request.getHotelConfirmationNo() != null) booking.setHotelConfirmationNo(request.getHotelConfirmationNo());
        if (request.getHotelName() != null) booking.setHotelName(request.getHotelName());
        if (request.getHotelCity() != null) booking.setHotelCity(request.getHotelCity());
        if (request.getHotelCountryCode() != null) booking.setHotelCountryCode(request.getHotelCountryCode());
        if (request.getHotelCheckIn() != null) booking.setHotelCheckIn(request.getHotelCheckIn());
        if (request.getHotelCheckOut() != null) booking.setHotelCheckOut(request.getHotelCheckOut());
        if (request.getHotelRoomType() != null) booking.setHotelRoomType(request.getHotelRoomType());
        if (request.getHotelBoardBasis() != null) booking.setHotelBoardBasis(request.getHotelBoardBasis());
        if (request.getHotelRooms() != null) booking.setHotelRooms(request.getHotelRooms());
        if (request.getVisaApplicationNo() != null) booking.setVisaApplicationNo(request.getVisaApplicationNo());
        if (request.getVisaCountry() != null) booking.setVisaCountry(request.getVisaCountry());
        if (request.getVisaAppliedDate() != null) booking.setVisaAppliedDate(request.getVisaAppliedDate());
        if (request.getVisaAppointmentDate() != null) booking.setVisaAppointmentDate(request.getVisaAppointmentDate());
        if (request.getVisaIssuedDate() != null) booking.setVisaIssuedDate(request.getVisaIssuedDate());
        if (request.getTransferVoucherNo() != null) booking.setTransferVoucherNo(request.getTransferVoucherNo());
        if (request.getTransferVehicleType() != null) booking.setTransferVehicleType(request.getTransferVehicleType());
        if (request.getTransferPickup() != null) booking.setTransferPickup(request.getTransferPickup());
        if (request.getTransferDropoff() != null) booking.setTransferDropoff(request.getTransferDropoff());
        if (request.getTransferDate() != null) booking.setTransferDate(request.getTransferDate());
        if (request.getTransferTime() != null) booking.setTransferTime(request.getTransferTime());
        // Cost and price come only from the vouchers (or Log Booking); a booking edit never sets them.
        if (request.getInternationalTrip() != null) booking.setInternationalTrip(request.getInternationalTrip());
        // Profit is never client-trusted - always recomputed server-side from the current values.
        booking.setProfit(computeProfit(booking.getSellingPrice(), booking.getNetCost()));
        checkTrip(booking);
        booking.setDepartureDate(deriveDepartureDate(booking));

        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        if (request.getPassengers() != null) {
            replacePassengers(booking.getId(), request.getPassengers());
        }
        if (alreadyConfirmed) {
            // A supplier picked only after confirming still owes their bill.
            draftSupplierBillOnce(booking);
        }
        // Same transaction as the save - a rollback loses the booking edit and its audit row together.
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        if (alreadyConfirmed) {
            raiseChangeAlerts(booking, changes);
        }
        reverseRecognitionIfRescheduledLater(booking, oldDepartureDate);
        log.info("Booking updated: bookingId={}, changedFields={}", id, changes.size());
        return toResponse(booking);
    }

    /**
     * ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 2.2.1 - type-agnostic, so the recognition job
     * never branches on booking type in SQL. Denormalisation by design, consistent with the
     * existing *_name snapshot columns.
     */
    private static void checkTrip(Booking booking) {
        TripRules.journey(booking.getType(), booking.getJourneyDate(), booking.getReturnDate());
        TripRules.stay(booking.getHotelCheckIn(), booking.getHotelCheckOut(), booking.getHotelRooms(), null);
        TripRules.transfer(booking.getTransferTime(), null);
    }

        private LocalDate deriveDepartureDate(Booking booking) {
        if (booking.getJourneyDate() != null) {
            return booking.getJourneyDate();
        }
        if (booking.getHotelCheckIn() != null) {
            return booking.getHotelCheckIn();
        }
        if (booking.getTransferDate() != null) {
            return booking.getTransferDate();
        }
        return null;
    }

    /**
     * Rule 2.5.1 - the only reschedule case needing code at the point of change. "Moved later,
     * not yet recognised" and any "moved earlier" case need nothing: the recognition job's live
     * query (Rule 2.4.1) already reads the current departure_date on every run. Only "moved
     * later while already recognised" must reverse here, because the job itself never sees a
     * date it didn't witness change. If no REVENUE_RECOGNIZED journal exists for any of this
     * booking's invoices, this is a no-op - the common case.
     */
    private void reverseRecognitionIfRescheduledLater(Booking booking, LocalDate oldDepartureDate) {
        LocalDate newDepartureDate = booking.getDepartureDate();
        if (newDepartureDate == null) {
            return;
        }
        if (oldDepartureDate != null && !newDepartureDate.isAfter(oldDepartureDate)) {
            return;
        }
        for (com.voyra.crm.entity.Invoice invoice : invoiceRepository.findByBookingId(booking.getId())) {
            journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    com.voyra.crm.enums.JournalSourceType.INVOICE, invoice.getId(),
                    com.voyra.crm.enums.JournalPurpose.REVENUE_RECOGNIZED)
                    .ifPresent(entry -> journalService.reverse(entry.getId(),
                            "Booking " + booking.getId() + " departure rescheduled to " + newDepartureDate
                                    + ", after revenue was already recognized"));
        }
    }

    /**
     * FRD US-ACC-1.1's "any modification to itinerary line items after confirmation triggers a
     * change notification in the accounting workbench." Raised in the same transaction as the
     * audit-log write above, in addition to it - not in place of it. Gated on the booking already
     * being CONFIRMED at the START of this call (captured before any field was touched), which is
     * the cheapest check available: no extra query, since {@code bookingStatus} is already loaded.
     * A booking still PENDING raises nothing, matching today's behaviour exactly.
     */
    private void raiseChangeAlerts(Booking booking, List<AuditChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        List<AccountingChangeAlert> alerts = changes.stream()
                .map(c -> AccountingChangeAlert.builder()
                        .id(UniqueIdResolver.resolve(accountingChangeAlertRepository::existsById))
                        .bookingId(booking.getId())
                        .fieldName(c.getField())
                        .oldValue(c.getOldValue())
                        .newValue(c.getNewValue())
                        .raisedAt(now)
                        .raisedBy(actor)
                        .acknowledged(false)
                        .build())
                .toList();
        accountingChangeAlertRepository.saveAll(alerts);
    }

    /**
     * FRD US-ACC-1.1: "the system verifies that the sum of supplier net costs plus agency markup
     * equals the total quoted client price before allowing financial record creation" - enforced
     * at the PENDING -&gt; CONFIRMED transition. Skipped entirely when the booking has no cost
     * components yet (the common case today - nothing to validate against), matching Decision 7,
     * Rule 7.4. A 0.01 tolerance absorbs rounding, not a real mismatch.
     */
    private void assertPriceIntegrity(Booking booking) {
        List<BookingCostComponent> components = bookingCostComponentRepository.findByBookingIdOrderBySortOrder(booking.getId());
        if (components.isEmpty()) {
            return;
        }
        BigDecimal componentTotal = components.stream()
                .map(BookingCostComponent::getNetCostInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal markup = computeMarkupAmount(booking, componentTotal);
        BigDecimal expectedSellingPrice = componentTotal.add(markup).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal actualSellingPrice = booking.getSellingPrice() != null ? booking.getSellingPrice() : BigDecimal.ZERO;
        BigDecimal variance = expectedSellingPrice.subtract(actualSellingPrice).abs();
        if (variance.compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalStateException("Supplier net costs (" + componentTotal + ") plus markup (" + markup
                    + ") = " + expectedSellingPrice + ", but the quoted selling price is " + actualSellingPrice
                    + " - a variance of " + variance + ". Correct the booking's cost components, markup, or "
                    + "selling price before confirming.");
        }
    }

    private BigDecimal computeMarkupAmount(Booking booking, BigDecimal componentTotal) {
        BigDecimal markupValue = booking.getMarkupValue() != null ? booking.getMarkupValue() : BigDecimal.ZERO;
        if (booking.getMarkupMode() == MarkupMode.PERCENT) {
            return componentTotal.multiply(markupValue)
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        return markupValue;
    }

    @Transactional
    public BookingResponse updateStatus(String id, BookingStatusUpdateRequest request) {
        if (request.getBookingStatus() == BookingStatus.CANCELLED) {
            // Cancelling works out refunds and posts accounting - see BookingCancellationService.
            throw new IllegalArgumentException("Use POST /api/bookings/{id}/cancel to cancel a booking");
        }
        // Locked first: two confirms at once must not both draft the supplier bill.
        bookingRepository.findByIdForUpdate(id);
        Booking booking = findAccessibleBooking(id);
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("A cancelled booking can't be reopened - create a new booking instead");
        }
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        boolean confirming = request.getBookingStatus() == BookingStatus.CONFIRMED
                && booking.getBookingStatus() == BookingStatus.PENDING;
        if (confirming) {
            assertReadyToConfirm(booking);
        }
        if (request.getBookingStatus() == BookingStatus.CONFIRMED && booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            assertPriceIntegrity(booking);
        }
        booking.setBookingStatus(request.getBookingStatus());
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        if (confirming) {
            draftSupplierBillOnce(booking);
        }
        log.info("Booking status updated: bookingId={}, status={}", id, request.getBookingStatus());
        return toResponse(booking);
    }

    /** The status half of a cancellation: audit, timeline and the lead service's status. The
     *  money half is BookingCancellationService's, which calls this inside its own transaction. */
    void markCancelled(Booking booking, String reason) {
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setCancelReason(reason);
        booking.setCancelledAt(LocalDateTime.now());
        booking.setCancelledBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        if (booking.getServiceId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(), LeadTimelineEventType.BOOKING_REMOVED,
                    labelOrType(booking) + " booking cancelled: " + reason);
            serviceBookingStatusSync.onBookingRemoved(booking.getServiceId());
        }
    }

    BookingResponse toResponsePublic(Booking booking) {
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateDeadlines(String id, BookingDeadlineUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        if (request.getTicketingDeadline() != null) booking.setTicketingDeadline(request.getTicketingDeadline());
        if (request.getCancellationDeadline() != null) booking.setCancellationDeadline(request.getCancellationDeadline());
        if (request.getDeadlineNote() != null) booking.setDeadlineNote(request.getDeadlineNote());
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updatePaymentStatus(String id, BookingPaymentStatusUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        if (booking.getPaymentStatusSource() == PaymentStatusSource.DERIVED) {
            throw new IllegalStateException("Payment status is derived from invoices - record a receipt instead");
        }
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        booking.setPaymentStatus(request.getPaymentStatus());
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        return toResponse(booking);
    }

    /**
     * The one destructive verb on a booking. Refused once any accounting or feedback record
     * references it - those five tables are all keyed on booking.id, and none of them tolerate
     * a dangling reference the way {@code shift_handover.pinned_booking_ids} (a plain array,
     * no FK) silently does.
     *
     * <p>Every change runs in one transaction, so a failure part-way leaves nothing half-deleted.
     * The rows are soft-deleted and the stored voucher files are kept.
     */
    public void deleteBooking(String id) {
        transactionTemplate.execute(status -> {
            deleteBookingRows(id);
            return null;
        });
    }

    private void deleteBookingRows(String id) {
        Booking booking = findAccessibleBooking(id);
        if (booking.getBookingStatus() != BookingStatus.PENDING) {
            throw new IllegalStateException("Only a pending booking can be deleted - cancel it instead");
        }
        if (bookingHasAccountingRecords(id)) {
            throw new IllegalStateException("This booking has accounting records - cancel it instead");
        }

        // A bill nobody has approved yet carries no postings - it goes with the booking.
        for (com.voyra.crm.entity.SupplierInvoice bill : unpostedBills(id)) {
            supplierInvoiceService.cancelForBooking(bill.getId(), "Booking deleted");
        }

        bookingDocumentRepository.deleteByBookingId(id);
        deletePassengers(id);
        bookingRepository.delete(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, id, labelFor(booking), List.of());

        if (booking.getServiceId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(), LeadTimelineEventType.BOOKING_REMOVED,
                    labelOrType(booking) + " booking removed");
            serviceBookingStatusSync.onBookingRemoved(booking.getServiceId());
        }
        log.info("Booking deleted: bookingId={}", id);
    }

    private boolean bookingHasAccountingRecords(String bookingId) {
        return invoiceRepository.existsByBookingId(bookingId)
                || paymentReceiptRepository.existsByBookingId(bookingId)
                || creditNoteRepository.existsByBookingIdAndStatusNot(bookingId, CreditNoteStatus.CANCELLED)
                || customerLedgerEntryRepository.existsByBookingId(bookingId)
                || feedbackRepository.existsByBookingId(bookingId)
                || supplierPaymentRepository.existsByBookingId(bookingId)
                // An approved (or paid) supplier bill is posted to the ledger - deleting the booking would orphan it.
                || supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED)
                        .size() > unpostedBills(bookingId).size();
    }

    private List<com.voyra.crm.entity.SupplierInvoice> unpostedBills(String bookingId) {
        return supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED)
                .stream().filter(b -> b.getStatus() == com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT
                        || b.getStatus() == com.voyra.crm.enums.SupplierInvoiceStatus.PENDING_APPROVAL)
                .toList();
    }

    /** Explicit, not a @PreUpdate - a JPA listener cannot reach the current principal (§5.3). */
    private void touch(Booking booking) {
        booking.setUpdatedAt(LocalDateTime.now());
        booking.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
    }

    /** BK-00042: five digits so numbers line up in lists, growing naturally past 99,999. */
    public static String bookingRef(Long bookingNumber) {
        return bookingNumber == null ? null : String.format("BK-%05d", bookingNumber);
    }

    private String labelFor(Booking b) {
        String ref = bookingRef(b.getBookingNumber());
        return (ref != null ? ref + " · " : "") + b.getClientName() + " / " + b.getDestination();
    }

    private String labelOrType(Booking b) {
        String type = b.getType().name();
        return b.getSupplier() != null ? b.getSupplier() : type.charAt(0) + type.substring(1).toLowerCase();
    }

    /** The free-text supplier as given, else the picked Vendor's name, so the booking always shows who it is with. */
    private String supplierName(String supplier, String vendorId) {
        if (supplier != null && !supplier.isBlank()) return supplier;
        if (vendorId == null || vendorId.isBlank()) return supplier;
        return vendorRepository.findById(vendorId).map(Vendor::getName).orElse(null);
    }

    /**
     * A voucher is how a booking gets booked and priced: its cost, and the selling price worked out
     * from its markup, add to the booking's totals; its reference becomes the booking's when it has
     * none; a voucher with a supplier and a cost drafts its own supplier bill; and the voucher that
     * makes a pending booking ready (reference + price) confirms it. Called inside the document's
     * own transaction, after the row lock.
     */
    void applyVoucher(Booking booking, BookingDocument voucher, String requestedVendorId, boolean overrideCreditLimit) {
        BigDecimal cost = voucher.getNetCost() != null ? voucher.getNetCost() : BigDecimal.ZERO;
        voucher.setSellingPrice(voucher.getMarkupMode() == null ? BigDecimal.ZERO
                : com.voyra.crm.util.MarkupMath.sellingPrice(cost, voucher.getMarkupMode(), voucher.getMarkupValue()));
        clientCreditService.assertWithinLimit(booking.getClientId(), voucher.getSellingPrice(), overrideCreditLimit);
        String vendorId = requestedVendorId != null && !requestedVendorId.isBlank() ? requestedVendorId : booking.getVendorId();
        voucher.setVendorId(vendorId);

        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        boolean wasConfirmed = booking.getBookingStatus() == BookingStatus.CONFIRMED;
        booking.setNetCost(booking.getNetCost().add(cost));
        booking.setSellingPrice(booking.getSellingPrice().add(voucher.getSellingPrice()));
        booking.setProfit(computeProfit(booking.getSellingPrice(), booking.getNetCost()));
        if (voucher.getMarkupMode() != null) {
            booking.setMarkupMode(voucher.getMarkupMode());
            booking.setMarkupValue(voucher.getMarkupValue());
        }
        if (blank(referenceOf(booking)) && !blank(voucher.getReferenceNumber())) {
            setReference(booking, voucher.getReferenceNumber().trim());
        }
        if (booking.getBookingStatus() == BookingStatus.PENDING && readyToConfirm(booking)) {
            booking.setBookingStatus(BookingStatus.CONFIRMED);
        }
        if (vendorId != null && cost.signum() > 0) {
            voucher.setSupplierInvoiceId(supplierInvoiceService.draftForVoucher(booking, vendorId, cost, voucher.getReferenceNumber()).getId());
        }
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        if (wasConfirmed) {
            raiseChangeAlerts(booking, changes);
        }
    }

    /**
     * Takes a voucher's amounts back off the booking and withdraws its still-unposted supplier bill.
     * Refused once that bill is approved, or when the booking would end up priced below what is
     * already invoiced.
     */
    void reverseVoucher(Booking booking, BookingDocument voucher) {
        reverseVoucher(booking, voucher, true);
    }

    /** @param guardInvoiced false while editing a voucher - the caller checks the price after the new amounts are in */
    void reverseVoucher(Booking booking, BookingDocument voucher, boolean guardInvoiced) {
        BigDecimal cost = voucher.getNetCost() != null ? voucher.getNetCost() : BigDecimal.ZERO;
        BigDecimal price = voucher.getSellingPrice() != null ? voucher.getSellingPrice() : BigDecimal.ZERO;
        if (cost.signum() == 0 && price.signum() == 0) {
            return;
        }
        if (voucher.getSupplierInvoiceId() != null) {
            com.voyra.crm.entity.SupplierInvoice bill = supplierInvoiceRepository.findById(voucher.getSupplierInvoiceId()).orElse(null);
            if (bill != null && bill.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED) {
                if (bill.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT
                        && bill.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.PENDING_APPROVAL) {
                    throw new IllegalStateException("This voucher's supplier bill is already approved - "
                            + (guardInvoiced ? "it can't be removed" : "its cost, markup or supplier can't be changed"));
                }
                supplierInvoiceService.cancelForBooking(bill.getId(), "Voucher removed");
            }
        }
        BigDecimal newPrice = booking.getSellingPrice().subtract(price);
        if (guardInvoiced && newPrice.compareTo(invoicedPrice(booking.getId())) < 0) {
            throw new IllegalStateException("This voucher's price is already invoiced - it can't be removed");
        }
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        booking.setNetCost(booking.getNetCost().subtract(cost).max(BigDecimal.ZERO));
        booking.setSellingPrice(newPrice.max(BigDecimal.ZERO));
        booking.setProfit(computeProfit(booking.getSellingPrice(), booking.getNetCost()));
        // Removing the last priced voucher un-books it: a booking is never "Confirmed" at ₹0.
        // (An edit re-applies the voucher straight after, so only a removal does this.)
        boolean unconfirmed = guardInvoiced && booking.getBookingStatus() == BookingStatus.CONFIRMED
                && booking.getSellingPrice().signum() == 0;
        if (unconfirmed) {
            booking.setBookingStatus(BookingStatus.PENDING);
        }
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        if (booking.getBookingStatus() == BookingStatus.CONFIRMED) {
            raiseChangeAlerts(booking, changes);
        }
    }

    /** Row lock first, then the usual access check - two vouchers saved at once must not lose either's amounts. */
    Booking lockAccessibleBooking(String id) {
        bookingRepository.findByIdForUpdate(id);
        return findAccessibleBooking(id);
    }

    /** Removes a voucher and its amounts in one transaction. @return its stored file key, deleted by the caller after commit */
    @Transactional
    public String removeVoucher(String bookingId, String documentId) {
        Booking booking = lockAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        assertNotInvoiced(document);
        reverseVoucher(booking, document);
        bookingDocumentRepository.delete(document);
        syncReference(booking, document.getReferenceNumber());
        return document.getFileKey();
    }

    /** When the booking's latest live tax invoice was issued; null when nothing is invoiced. */
    LocalDateTime lastInvoicedAt(String bookingId) {
        return invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                        bookingId, com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE, com.voyra.crm.enums.InvoiceLifecycle.CANCELLED).stream()
                .filter(i -> i.getStatus() != com.voyra.crm.enums.InvoiceLifecycle.DRAFT && i.getIssuedAt() != null)
                .map(com.voyra.crm.entity.Invoice::getIssuedAt).max(LocalDateTime::compareTo).orElse(null);
    }

    /** A voucher is invoiced once an issued invoice came after it; one added later is open until its own invoice. */
    static boolean isInvoiced(BookingDocument d, LocalDateTime invoicedAt) {
        return invoicedAt != null && d.getCreatedAt() != null && !d.getCreatedAt().isAfter(invoicedAt);
    }

    void assertNotInvoiced(BookingDocument document) {
        if (isInvoiced(document, lastInvoicedAt(document.getBookingId()))) {
            throw new IllegalStateException("This voucher is already invoiced - it can't be changed or removed");
        }
    }

    /** The selling price already on the booking's live tax invoices, before GST. */
    public BigDecimal invoicedPrice(String bookingId) {
        return invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                        bookingId, com.voyra.crm.enums.InvoiceDocumentType.TAX_INVOICE, com.voyra.crm.enums.InvoiceLifecycle.CANCELLED).stream()
                .filter(i -> i.getStatus() != com.voyra.crm.enums.InvoiceLifecycle.DRAFT)
                .flatMap(i -> invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(i.getId()).stream())
                .map(l -> l.getLineSubtotal() != null ? l.getLineSubtotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String referenceOf(Booking b) {
        return switch (b.getType()) {
            case HOTEL -> b.getHotelConfirmationNo();
            case VISA -> b.getVisaApplicationNo();
            case TRANSFER -> b.getTransferVoucherNo();
            default -> b.getPnr();
        };
    }

    private static void setReference(Booking b, String reference) {
        if (reference != null && reference.length() > 100) reference = reference.substring(0, 100);
        switch (b.getType()) {
            case HOTEL -> b.setHotelConfirmationNo(reference);
            case VISA -> b.setVisaApplicationNo(reference);
            case TRANSFER -> b.setTransferVoucherNo(reference);
            default -> b.setPnr(reference);
        }
    }

    /**
     * The booking's reference follows its live vouchers, re-derived after every voucher add, edit
     * or removal: the priced vouchers' references, else any voucher's. With no voucher reference
     * left, a reference that only {@code staleReference} (the removed or edited voucher's old one)
     * gave is cleared; one typed on the booking itself is kept.
     */
    void syncReference(Booking booking, String staleReference) {
        List<BookingDocument> live = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(booking.getId()).stream()
                .filter(d -> !blank(d.getReferenceNumber()))
                .sorted(java.util.Comparator.comparing(BookingDocument::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        List<BookingDocument> priced = live.stream()
                .filter(d -> d.getSellingPrice() != null && d.getSellingPrice().signum() > 0).toList();
        List<String> refs = (priced.isEmpty() ? live : priced).stream()
                .map(d -> d.getReferenceNumber().trim()).distinct().toList();
        String current = referenceOf(booking);
        String next;
        if (!refs.isEmpty()) {
            next = String.join(", ", refs);
        } else if (!blank(staleReference) && !blank(current) && current.contains(staleReference.trim())) {
            next = null;
        } else {
            return;
        }
        if (java.util.Objects.equals(current, next)) return;
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        setReference(booking, next);
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking),
                AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED)));
    }

    private static boolean readyToConfirm(Booking b) {
        return !blank(referenceOf(b)) && b.getSellingPrice() != null && b.getSellingPrice().signum() > 0;
    }

    /** A booking is confirmed once it is really booked: the supplier's reference and the price are on it. */
    static void assertReadyToConfirm(Booking b) {
        if (!readyToConfirm(b)) {
            throw new IllegalArgumentException("Add the booking reference and selling price before confirming");
        }
    }

    /**
     * Confirmed with the supplier = their bill is due. A booking with a real Vendor (not just a
     * free-text supplier name) and a cost drafts its supplier bill for Accounts > Supplier Bills,
     * from the cost as it stands at confirmation - a pending booking's fare is still moving. Once:
     * a booking that already has a live bill keeps that one.
     */
    private void draftSupplierBillOnce(Booking booking) {
        if (booking.getVendorId() != null && !booking.getVendorId().isBlank()
                && booking.getNetCost() != null && booking.getNetCost().compareTo(BigDecimal.ZERO) > 0
                && supplierInvoiceRepository.findByBookingIdAndStatusNot(booking.getId(),
                        com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED).isEmpty()) {
            supplierInvoiceService.createAutoDraft(booking, booking.getVendorId());
        }
    }

    /** Points the booking at a Vendor. Refused once a supplier bill is on the booking - that bill is
     *  the other vendor's, so it has to be cancelled first in Accounts. */
    private void setVendor(Booking booking, String vendorId) {
        if (vendorId.isBlank()) return;
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + vendorId));
        // Only the default for new vouchers: each voucher keeps its own supplier and bill.
        booking.setVendorId(vendor.getId());
        booking.setSupplier(vendor.getName());
    }

    private BigDecimal computeProfit(BigDecimal sellingPrice, BigDecimal netCost) {
        BigDecimal selling = sellingPrice != null ? sellingPrice : BigDecimal.ZERO;
        BigDecimal cost = netCost != null ? netCost : BigDecimal.ZERO;
        return selling.subtract(cost);
    }

    /** A standalone booking is gated by its own type, as a service-linked one is by its service: the
     *  agent it belongs to must handle that type, whoever creates it (an owner booking "for" an agent
     *  included). PACKAGE has no service type. */
    private void assertAgentHandlesStandaloneType(BookingType type, String agentId) {
        ServiceType serviceType = type != null ? ServiceBookingTypeMapper.forBooking(type) : null;
        if (serviceType == null) {
            return;
        }
        Agent agent = agentRepository.findById(agentId).orElse(null);
        List<ServiceType> handled = agent != null ? agent.getManageableServices() : null;
        if (handled == null || !handled.contains(serviceType)) {
            if (SecurityContextUtil.getCurrentUserOrThrow().isAgent()) {
                throw new AccessDeniedException("You don't handle " + serviceType + " - only agents who do can create a " + serviceType + " booking");
            }
            throw new IllegalArgumentException((agent != null ? agent.getName() : "That agent") + " doesn't handle "
                    + serviceType + " - pick an agent who does");
        }
    }

    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a booking");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    /**
     * (a) the caller logged it, or (b) the caller is the service's assigned agent, or (c) the
     * caller manages the service's type - see {@link BookingAccessChecker}. Tried once with no
     * agent lookup at all (covers (a) and (b), which every existing booking test's fixtures
     * satisfy), and the agent is only loaded if that first, cheap check fails.
     */
    public Booking findAccessibleBooking(String id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgent()) {
            return booking;
        }
        String agentId = principal.userId();
        if (BookingAccessChecker.agentCanAccess(booking, agentId, List.of())) {
            return booking;
        }
        Agent agent = agentRepository.findById(agentId).orElse(null);
        List<ServiceType> manageableTypes = agent != null ? agent.getManageableServices() : List.of();
        if (!BookingAccessChecker.agentCanAccess(booking, agentId, manageableTypes)) {
            throw new AccessDeniedException("This booking is not accessible to you");
        }
        return booking;
    }

    private BookingResponse toResponse(Booking b) {
        LocalDateTime invoicedAt = lastInvoicedAt(b.getId());
        List<BookingDocument> docs = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(b.getId());
        java.util.Set<String> approvedBills = approvedBillIds(docs);
        List<BookingDocumentResponse> documents = docs.stream().map(d -> toDocumentResponse(d, invoicedAt, approvedBills)).toList();
        BigDecimal marginPercent = MarginCalculator.marginPercent(b.getNetCost(), b.getSellingPrice());
        boolean lowMargin = b.getSellingPrice() != null && b.getSellingPrice().compareTo(BigDecimal.ZERO) > 0
                && marginPercent.compareTo(lowMarginThreshold()) < 0;
        return BookingResponse.builder()
                .id(b.getId()).bookingNumber(b.getBookingNumber()).bookingRef(bookingRef(b.getBookingNumber()))
                .clientId(b.getClientId()).clientName(b.getClientName())
                .agentId(b.getAgentId()).agentName(b.getAgentName())
                .leadId(b.getLeadId()).serviceId(b.getServiceId()).serviceType(b.getServiceType())
                .serviceLabel(b.getServiceLabel()).serviceAgentId(b.getServiceAgentId()).serviceAgentName(b.getServiceAgentName())
                .type(b.getType())
                .destination(b.getDestination()).pnr(b.getPnr()).ticketNo(b.getTicketNo())
                .airline(b.getAirline()).supplier(b.getSupplier()).vendorId(b.getVendorId()).journeyDate(b.getJourneyDate())
                .returnDate(b.getReturnDate()).tripType(b.getTripType()).notes(b.getNotes())
                .flightNumber(b.getFlightNumber()).flightFrom(b.getFlightFrom()).flightTo(b.getFlightTo())
                .flightCabin(b.getFlightCabin())
                .flightDepartTime(b.getFlightDepartTime()).flightArriveTime(b.getFlightArriveTime())
                .flightArriveDate(b.getFlightArriveDate())
                .hotelConfirmationNo(b.getHotelConfirmationNo()).hotelName(b.getHotelName()).hotelCity(b.getHotelCity())
                .hotelCountryCode(b.getHotelCountryCode())
                .hotelCheckIn(b.getHotelCheckIn()).hotelCheckOut(b.getHotelCheckOut())
                .hotelRoomType(b.getHotelRoomType()).hotelBoardBasis(b.getHotelBoardBasis()).hotelRooms(b.getHotelRooms())
                .visaApplicationNo(b.getVisaApplicationNo()).visaCountry(b.getVisaCountry())
                .visaAppliedDate(b.getVisaAppliedDate()).visaAppointmentDate(b.getVisaAppointmentDate())
                .visaIssuedDate(b.getVisaIssuedDate())
                .transferVoucherNo(b.getTransferVoucherNo()).transferVehicleType(b.getTransferVehicleType())
                .transferPickup(b.getTransferPickup()).transferDropoff(b.getTransferDropoff())
                .transferDate(b.getTransferDate()).transferTime(b.getTransferTime())
                .netCost(b.getNetCost())
                .sellingPrice(b.getSellingPrice()).profit(b.getProfit())
                .markupMode(b.getMarkupMode()).markupValue(b.getMarkupValue()).bookingStatus(b.getBookingStatus())
                .paymentStatus(b.getPaymentStatus()).bookingDate(b.getBookingDate())
                .cancelReason(b.getCancelReason()).refundStatus(b.getRefundStatus())
                .refundState(b.getRefundState()).refundAmount(b.getRefundAmount())
                .refundDueDate(b.getRefundDueDate()).refundedAt(b.getRefundedAt())
                .cancelledAt(b.getCancelledAt())
                .ticketingDeadline(b.getTicketingDeadline()).cancellationDeadline(b.getCancellationDeadline())
                .deadlineNote(b.getDeadlineNote())
                .createdDate(b.getCreatedDate())
                .primaryInvoiceId(b.getPrimaryInvoiceId())
                .invoicedTotalInr(b.getInvoicedTotalInr()).receivedTotalInr(b.getReceivedTotalInr())
                .refundedTotalInr(b.getRefundedTotalInr()).paymentStatusSource(b.getPaymentStatusSource())
                .cancellationSupplierCharge(b.getCancellationSupplierCharge()).cancellationAirlineCharge(b.getCancellationAirlineCharge())
                .cancellationAgencyFee(b.getCancellationAgencyFee())
                .customerRefundDue(b.getCustomerRefundDue()).supplierRefundExpected(b.getSupplierRefundExpected())
                .supplierRefundReceived(b.getSupplierRefundReceived())
                .supplierRefundClaimAmount(b.getSupplierRefundClaimAmount())
                .supplierRefundClaimBy(b.getSupplierRefundClaimBy())
                .cancellationType(b.getCancellationType())
                .refundCheckedAt(b.getRefundCheckedAt()).refundCheckedBy(b.getRefundCheckedBy())
                .documents(documents)
                .nextStep(com.voyra.crm.util.BookingNextStep.of(b, documents.size()))
                .internationalTrip(b.getInternationalTrip())
                .marginPercent(marginPercent).lowMargin(lowMargin)
                .passengers(toPassengerResponses(b.getId()))
                .build();
    }

    /** Paid to the booking's supplier so far. shortcut: one query per booking row, batch it if list pages grow past ~100. */
    /** The agency's own setting when present, else {@link #DEFAULT_LOW_MARGIN_THRESHOLD_PERCENT}. */
    private BigDecimal lowMarginThreshold() {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        return tenant != null && tenant.getLowMarginThresholdPercent() != null
                ? tenant.getLowMarginThresholdPercent()
                : DEFAULT_LOW_MARGIN_THRESHOLD_PERCENT;
    }

    private List<BookingPassengerResponse> toPassengerResponses(String bookingId) {
        List<BookingPassenger> passengers = bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(bookingId);
        if (passengers.isEmpty()) {
            return List.of();
        }
        List<String> passengerIds = passengers.stream().map(BookingPassenger::getId).toList();
        Map<String, List<BookingSector>> sectorsByPassenger = bookingSectorRepository
                .findByBookingPassengerIdInOrderBySortOrderAsc(passengerIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(BookingSector::getBookingPassengerId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return passengers.stream().map(p -> BookingPassengerResponse.builder()
                .id(p.getId()).leadMemberId(p.getLeadMemberId()).passengerName(p.getPassengerName())
                .paxType(p.getPaxType()).fareAmount(p.getFareAmount()).taxAmount(p.getTaxAmount())
                .sectors(sectorsByPassenger.getOrDefault(p.getId(), List.of()).stream()
                        .map(s -> BookingSectorResponse.builder()
                                .id(s.getId()).sectorFrom(s.getSectorFrom()).sectorTo(s.getSectorTo())
                                .flightNumber(s.getFlightNumber()).travelDate(s.getTravelDate())
                                .cabinClass(s.getCabinClass())
                                .build())
                        .toList())
                .build())
                .toList();
    }

    /** Replaces the whole passenger (and sector) list for a booking - same save-the-whole-set
     *  semantics as an invoice's lines. Existing sectors are deleted by passenger id since a
     *  passenger's own id is regenerated on every replace, not reused. */
    private void replacePassengers(String bookingId, List<BookingPassengerRequest> requests) {
        deletePassengers(bookingId);
        LocalDateTime now = LocalDateTime.now();
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        int sortOrder = 0;
        for (BookingPassengerRequest req : requests) {
            BookingPassenger passenger = BookingPassenger.builder()
                    .id(UniqueIdResolver.resolve(bookingPassengerRepository::existsById))
                    .bookingId(bookingId)
                    .leadMemberId(req.getLeadMemberId())
                    .passengerName(req.getPassengerName())
                    .paxType(req.getPaxType())
                    .fareAmount(req.getFareAmount())
                    .taxAmount(req.getTaxAmount() != null ? req.getTaxAmount() : java.math.BigDecimal.ZERO)
                    .sortOrder(sortOrder++)
                    .createdAt(now)
                    .createdBy(actor)
                    .build();
            bookingPassengerRepository.save(passenger);

            if (req.getSectors() != null) {
                int sectorOrder = 0;
                List<BookingSector> sectors = new ArrayList<>();
                for (BookingSectorRequest sReq : req.getSectors()) {
                    sectors.add(BookingSector.builder()
                            .id(UniqueIdResolver.resolve(bookingSectorRepository::existsById))
                            .bookingPassengerId(passenger.getId())
                            .sortOrder(sectorOrder++)
                            .sectorFrom(sReq.getSectorFrom())
                            .sectorTo(sReq.getSectorTo())
                            .flightNumber(sReq.getFlightNumber())
                            .travelDate(sReq.getTravelDate())
                            .cabinClass(sReq.getCabinClass())
                            .createdAt(now)
                            .build());
                }
                bookingSectorRepository.saveAll(sectors);
            }
        }
    }

    private void deletePassengers(String bookingId) {
        List<String> passengerIds = bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(bookingId)
                .stream().map(BookingPassenger::getId).toList();
        if (!passengerIds.isEmpty()) {
            bookingSectorRepository.deleteByBookingPassengerIdIn(passengerIds);
        }
        bookingPassengerRepository.deleteByBookingId(bookingId);
    }

    @Transactional(readOnly = true)
    public List<BookingCostComponentResponse> listCostComponents(String bookingId) {
        findAccessibleBooking(bookingId);
        return toCostComponentResponses(bookingId);
    }

    /**
     * Replaces the whole cost-component list for a booking - same save-the-whole-set semantics
     * as {@link #replacePassengers}. When the resulting list is non-empty, {@code booking.netCost}
     * (and the profit derived from it) is recomputed as the live-synced sum of the components'
     * {@code netCostInr} in this same transaction (Decision 7, Rule 7.3). {@code booking.vendorId}
     * is left untouched - it stays the primary-vendor snapshot for list screens. Clearing the list
     * back to empty deliberately leaves {@code booking.netCost} at its last value rather than
     * zeroing it - an explicit "no components" state should not silently erase a manually-entered
     * cost.
     */
    @Transactional
    public List<BookingCostComponentResponse> replaceCostComponents(String bookingId, List<BookingCostComponentRequest> requests) {
        Booking booking = findAccessibleBooking(bookingId);
        bookingCostComponentRepository.deleteByBookingId(bookingId);

        LocalDateTime now = LocalDateTime.now();
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        List<BookingCostComponent> components = new ArrayList<>();
        int sortOrder = 0;
        for (BookingCostComponentRequest req : requests) {
            String currencyCode = req.getCurrencyCode() != null && !req.getCurrencyCode().isBlank() ? req.getCurrencyCode() : "INR";
            BigDecimal fxRate = "INR".equals(currencyCode)
                    ? BigDecimal.ONE
                    : (req.getFxRateToInr() != null ? req.getFxRateToInr() : BigDecimal.ONE);
            BigDecimal netCost = req.getNetCost() != null ? req.getNetCost() : BigDecimal.ZERO;
            String vendorName = null;
            if (req.getVendorId() != null && !req.getVendorId().isBlank()) {
                vendorName = vendorRepository.findById(req.getVendorId()).map(Vendor::getName).orElse(null);
            }

            components.add(BookingCostComponent.builder()
                    .id(UniqueIdResolver.resolve(bookingCostComponentRepository::existsById))
                    .bookingId(bookingId)
                    .bookingPassengerId(req.getBookingPassengerId())
                    .serviceType(req.getServiceType())
                    .vendorId(req.getVendorId())
                    .vendorName(vendorName)
                    .description(req.getDescription())
                    .netCost(netCost)
                    .currencyCode(currencyCode)
                    .fxRateToInr(fxRate)
                    .netCostInr(netCost.multiply(fxRate).setScale(2, java.math.RoundingMode.HALF_UP))
                    .dueDate(req.getDueDate())
                    .sortOrder(sortOrder++)
                    .createdAt(now)
                    .createdBy(actor)
                    .build());
        }
        bookingCostComponentRepository.saveAll(components);

        if (!components.isEmpty()) {
            boolean alreadyConfirmed = booking.getBookingStatus() == BookingStatus.CONFIRMED;
            BigDecimal recomputedNetCost = components.stream()
                    .map(BookingCostComponent::getNetCostInr)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
            booking.setNetCost(recomputedNetCost);
            booking.setProfit(computeProfit(booking.getSellingPrice(), recomputedNetCost));
            touch(booking);
            bookingRepository.save(booking);
            List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
            auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
            // Net cost and profit just moved under an already-confirmed booking - the same
            // accounting-workbench notification a direct edit of those fields raises.
            if (alreadyConfirmed) {
                raiseChangeAlerts(booking, changes);
            }
        }

        log.info("Booking cost components replaced: bookingId={}, count={}", bookingId, components.size());
        return toCostComponentResponses(bookingId);
    }

    private List<BookingCostComponentResponse> toCostComponentResponses(String bookingId) {
        return bookingCostComponentRepository.findByBookingIdOrderBySortOrder(bookingId).stream()
                .map(c -> BookingCostComponentResponse.builder()
                        .id(c.getId()).bookingPassengerId(c.getBookingPassengerId())
                        .serviceType(c.getServiceType()).vendorId(c.getVendorId()).vendorName(c.getVendorName())
                        .description(c.getDescription()).netCost(c.getNetCost())
                        .currencyCode(c.getCurrencyCode()).fxRateToInr(c.getFxRateToInr()).netCostInr(c.getNetCostInr())
                        .dueDate(c.getDueDate()).sortOrder(c.getSortOrder())
                        .build())
                .toList();
    }

    /** The vouchers' supplier bills that are past approval - their money can no longer change. */
    java.util.Set<String> approvedBillIds(List<BookingDocument> docs) {
        List<String> ids = docs.stream().map(BookingDocument::getSupplierInvoiceId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return java.util.Set.of();
        return supplierInvoiceRepository.findAllById(ids).stream()
                .filter(i -> i.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT
                        && i.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.PENDING_APPROVAL
                        && i.getStatus() != com.voyra.crm.enums.SupplierInvoiceStatus.CANCELLED)
                .map(com.voyra.crm.entity.SupplierInvoice::getId).collect(java.util.stream.Collectors.toSet());
    }

    static BookingDocumentResponse toDocumentResponse(BookingDocument d, LocalDateTime invoicedAt, java.util.Set<String> approvedBills) {
        return BookingDocumentResponse.builder()
                .details(d.getDetails()).invoiced(isInvoiced(d, invoicedAt))
                .billApproved(d.getSupplierInvoiceId() != null && approvedBills.contains(d.getSupplierInvoiceId()))
                .id(d.getId()).bookingId(d.getBookingId()).docType(d.getDocType())
                .referenceNumber(d.getReferenceNumber()).issuedDate(d.getIssuedDate())
                .hasFile(d.getFileKey() != null).fileName(d.getFileName())
                .notes(d.getNotes()).createdAt(d.getCreatedAt())
                .netCost(d.getNetCost()).sellingPrice(d.getSellingPrice())
                .markupMode(d.getMarkupMode()).markupValue(d.getMarkupValue()).vendorId(d.getVendorId())
                .build();
    }

    private String generateUniqueBookingId() {
        return UniqueIdResolver.resolve(bookingRepository::existsById);
    }
}
