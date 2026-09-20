package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingDeadlineUpdateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingRefundUpdateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.FeedbackRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.spec.BookingSpecifications;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.BookingAccessChecker;
import com.voyra.crm.util.ServiceBookingTypeMapper;
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
            "flightNumber", "flightFrom", "flightTo", "flightCabin",
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
    private final ClientRepository clientRepository;
    private final AgentRepository agentRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final CustomerLedgerEntryRepository customerLedgerEntryRepository;
    private final FeedbackRepository feedbackRepository;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;
    private final LeadTimelineService leadTimelineService;
    private final ServiceInstanceService serviceInstanceService;
    private final ServiceBookingStatusSync serviceBookingStatusSync;

    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());
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

        Booking.BookingBuilder builder = Booking.builder()
                .id(generateUniqueBookingId())
                .clientId(client.getId())
                .clientName(client.getName())
                .agentId(owner.id())
                .agentName(owner.name())
                .type(request.getType())
                .destination(request.getDestination())
                .pnr(request.getPnr())
                .ticketNo(request.getTicketNo())
                .airline(request.getAirline())
                .supplier(request.getSupplier())
                .journeyDate(request.getJourneyDate())
                .returnDate(request.getReturnDate())
                .tripType(request.getTripType())
                .notes(request.getNotes())
                .flightNumber(request.getFlightNumber())
                .flightFrom(request.getFlightFrom())
                .flightTo(request.getFlightTo())
                .flightCabin(request.getFlightCabin())
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
        }

        Booking booking = builder.build();
        bookingRepository.save(booking);
        auditService.recordCreate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking));

        if (service != null) {
            leadTimelineService.record(service.getLeadId(), service.getId(), LeadTimelineEventType.BOOKING_LOGGED,
                    labelOrType(booking) + " booking logged" + (booking.getPnr() != null ? " (" + booking.getPnr() + ")" : ""));
            serviceBookingStatusSync.onBookingLogged(service.getId());
        }

        log.info("Booking created: bookingId={}, agentId={}, serviceId={}", booking.getId(), owner.id(), request.getServiceId());
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter) {
        return bookingRepository.findAll(scopeSpecification(typeFilter, statusFilter)).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter, Pageable pageable) {
        Page<Booking> page = bookingRepository.findAll(scopeSpecification(typeFilter, statusFilter), pageable);
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * Built as a {@code Specification} rather than a chain of derived finders - see
     * {@code BookingSpecifications}'s javadoc. Exactly two queries total per call: one
     * {@code agentRepository.findById} to read {@code manageableServices} (agent callers only),
     * one {@code findAll(spec)} - independent of how many bookings exist.
     */
    private Specification<Booking> scopeSpecification(BookingType typeFilter, BookingStatus statusFilter) {
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
        Booking booking = findAccessibleBooking(id);
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);

        if (request.getPnr() != null) booking.setPnr(request.getPnr());
        if (request.getTicketNo() != null) booking.setTicketNo(request.getTicketNo());
        if (request.getAirline() != null) booking.setAirline(request.getAirline());
        if (request.getSupplier() != null) booking.setSupplier(request.getSupplier());
        if (request.getJourneyDate() != null) booking.setJourneyDate(request.getJourneyDate());
        if (request.getReturnDate() != null) booking.setReturnDate(request.getReturnDate());
        if (request.getTripType() != null) booking.setTripType(request.getTripType());
        if (request.getNotes() != null) booking.setNotes(request.getNotes());
        if (request.getFlightNumber() != null) booking.setFlightNumber(request.getFlightNumber());
        if (request.getFlightFrom() != null) booking.setFlightFrom(request.getFlightFrom());
        if (request.getFlightTo() != null) booking.setFlightTo(request.getFlightTo());
        if (request.getFlightCabin() != null) booking.setFlightCabin(request.getFlightCabin());
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
        if (request.getNetCost() != null) booking.setNetCost(request.getNetCost());
        if (request.getSellingPrice() != null) booking.setSellingPrice(request.getSellingPrice());
        // Profit is never client-trusted - always recomputed server-side from the current values.
        booking.setProfit(computeProfit(booking.getSellingPrice(), booking.getNetCost()));

        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        // Same transaction as the save - a rollback loses the booking edit and its audit row together.
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        log.info("Booking updated: bookingId={}, changedFields={}", id, changes.size());
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateStatus(String id, BookingStatusUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        if (request.getBookingStatus() == BookingStatus.CANCELLED
                && (request.getCancelReason() == null || request.getCancelReason().isBlank())) {
            throw new IllegalArgumentException("A reason is required when cancelling a booking");
        }
        booking.setBookingStatus(request.getBookingStatus());
        if (request.getBookingStatus() == BookingStatus.CANCELLED) {
            booking.setCancelReason(request.getCancelReason());
            if (request.getRefundStatus() != null) {
                booking.setRefundStatus(request.getRefundStatus());
            }
            // A cancellation always needs a refund decision from someone - defaulting to
            // NOT_APPLICABLE here would make every cancelled booking silently invisible to the
            // "who still owes us money" view. updateRefund() moves it on from here.
            if (booking.getRefundState() == RefundState.NOT_APPLICABLE) {
                booking.setRefundState(RefundState.REFUND_PENDING);
            }
            booking.setCancelledAt(LocalDateTime.now());
            booking.setCancelledBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        }
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);

        if (request.getBookingStatus() == BookingStatus.CANCELLED && booking.getServiceId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(), LeadTimelineEventType.BOOKING_REMOVED,
                    labelOrType(booking) + " booking cancelled");
            serviceBookingStatusSync.onBookingRemoved(booking.getServiceId());
        }
        log.info("Booking status updated: bookingId={}, status={}", id, request.getBookingStatus());
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateRefund(String id, BookingRefundUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        if (booking.getBookingStatus() != BookingStatus.CANCELLED) {
            throw new IllegalStateException("Only a cancelled booking can carry a refund state");
        }
        if (creditNoteRepository.existsByBookingIdAndStatusNot(id, CreditNoteStatus.CANCELLED)) {
            throw new IllegalStateException("A credit note exists for this booking - refund state is derived from it");
        }
        Map<String, String> before = AuditSnapshot.of(booking, AUDITED);
        booking.setRefundState(request.getRefundState());
        if (request.getRefundAmount() != null) {
            booking.setRefundAmount(request.getRefundAmount());
        }
        if (request.getRefundDueDate() != null) {
            booking.setRefundDueDate(request.getRefundDueDate());
        }
        if (request.getRefundState() == RefundState.REFUNDED || request.getRefundState() == RefundState.PARTIALLY_REFUNDED) {
            booking.setRefundedAt(LocalDateTime.now());
        }
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(booking, AUDITED));
        touch(booking);
        bookingRepository.save(booking);
        auditService.recordUpdate(AuditEntityType.BOOKING, booking.getId(), labelFor(booking), changes);
        log.info("Booking refund updated: bookingId={}, refundState={}", id, request.getRefundState());
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
     * <p>Not {@code @Transactional} as one block - deleting each document's stored file is
     * object storage work blueprint §8.6 forbids inside a transaction, the same reason
     * {@code BookingDocumentService.deleteDocument} isn't either. The row deletes are each
     * self-transactional (Spring Data wraps every repository call).
     */
    public void deleteBooking(String id) {
        Booking booking = findAccessibleBooking(id);
        if (bookingHasAccountingRecords(id)) {
            throw new IllegalStateException("This booking has accounting records - cancel it instead");
        }

        List<BookingDocument> documents = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(id);
        bookingDocumentRepository.deleteByBookingId(id);
        documents.stream().map(BookingDocument::getFileKey).filter(java.util.Objects::nonNull)
                .forEach(fileStorageService::delete);

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
                || feedbackRepository.existsByBookingId(bookingId);
    }

    /** Explicit, not a @PreUpdate - a JPA listener cannot reach the current principal (§5.3). */
    private void touch(Booking booking) {
        booking.setUpdatedAt(LocalDateTime.now());
        booking.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
    }

    private String labelFor(Booking b) {
        return b.getClientName() + " / " + b.getDestination();
    }

    private String labelOrType(Booking b) {
        return b.getSupplier() != null ? b.getSupplier() : b.getType().name();
    }

    private BigDecimal computeProfit(BigDecimal sellingPrice, BigDecimal netCost) {
        BigDecimal selling = sellingPrice != null ? sellingPrice : BigDecimal.ZERO;
        BigDecimal cost = netCost != null ? netCost : BigDecimal.ZERO;
        return selling.subtract(cost);
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
        List<BookingDocumentResponse> documents = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(b.getId())
                .stream().map(this::toDocumentResponse).toList();
        return BookingResponse.builder()
                .id(b.getId()).clientId(b.getClientId()).clientName(b.getClientName())
                .agentId(b.getAgentId()).agentName(b.getAgentName())
                .leadId(b.getLeadId()).serviceId(b.getServiceId()).serviceType(b.getServiceType())
                .serviceLabel(b.getServiceLabel()).serviceAgentId(b.getServiceAgentId()).serviceAgentName(b.getServiceAgentName())
                .type(b.getType())
                .destination(b.getDestination()).pnr(b.getPnr()).ticketNo(b.getTicketNo())
                .airline(b.getAirline()).supplier(b.getSupplier()).journeyDate(b.getJourneyDate())
                .returnDate(b.getReturnDate()).tripType(b.getTripType()).notes(b.getNotes())
                .flightNumber(b.getFlightNumber()).flightFrom(b.getFlightFrom()).flightTo(b.getFlightTo())
                .flightCabin(b.getFlightCabin())
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
                .sellingPrice(b.getSellingPrice()).profit(b.getProfit()).bookingStatus(b.getBookingStatus())
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
                .documents(documents)
                .build();
    }

    private BookingDocumentResponse toDocumentResponse(BookingDocument d) {
        return BookingDocumentResponse.builder()
                .id(d.getId()).bookingId(d.getBookingId()).docType(d.getDocType())
                .referenceNumber(d.getReferenceNumber()).issuedDate(d.getIssuedDate())
                .hasFile(d.getFileKey() != null).fileName(d.getFileName())
                .notes(d.getNotes()).createdAt(d.getCreatedAt())
                .build();
    }

    private String generateUniqueBookingId() {
        return UniqueIdResolver.resolve(bookingRepository::existsById);
    }
}
