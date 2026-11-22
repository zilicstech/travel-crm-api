package com.voyra.crm.service;

import com.voyra.crm.dto.CancelRequestResponse;
import com.voyra.crm.dto.CancellationDetailResponse;
import com.voyra.crm.dto.CancellationFollowUpRequest;
import com.voyra.crm.dto.CancellationFollowUpResponse;
import com.voyra.crm.dto.CancellationFollowUpUpdateRequest;
import com.voyra.crm.dto.CancellationRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CancellationFollowUp;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CancellationFollowUpRepository;
import com.voyra.crm.repository.PlatformCancelRequestRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.spec.BookingSpecifications;
import com.voyra.crm.repository.spec.CancellationSpecifications;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import com.voyra.crm.util.CancellationStatusRules;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Cancellations tab: every booking that is cancelled, has a Voyra cancel request or has a cancellation follow-up,
 * with a status worked out from those facts ({@link CancellationStatusRules}), plus the follow-ups people add. The
 * caller's booking scope applies (an agent sees the bookings he may open); Voyra requests are read in their agency view.
 */
@Service
@RequiredArgsConstructor
public class CancellationService {

    /** Which bookings a list shows: everything, what still needs someone, or what is due today or earlier. */
    public enum View { OPEN, DUE, ALL }

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "bookingNumber");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final BookingRepository bookingRepository;
    private final CancellationFollowUpRepository followUpRepository;
    private final PlatformCancelRequestRepository requestRepository;
    private final PlatformCancelRequestService requestService;
    private final BookingService bookingService;
    private final BookingActivityService activityService;
    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final AuthorResolver authorResolver;
    Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public PagedResponse<CancellationRowResponse> list(View view, String query, String assignedTo, Pageable pageable) {
        LocalDate today = today();
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        Specification<Booking> spec = Specification.where(scope()).and(switch (view) {
            case ALL -> CancellationSpecifications.onTab(tenantId);
            case OPEN -> CancellationSpecifications.open(tenantId);
            case DUE -> CancellationSpecifications.due(tenantId, today);
        });
        if (query != null && !query.isBlank()) {
            spec = spec.and(BookingSpecifications.searchMatches(query));
        }
        if (assignedTo != null && !assignedTo.isBlank()) {
            spec = spec.and(CancellationSpecifications.openFollowUpAssignedTo(assignedTo));
        }
        if (view == View.DUE) {
            // shortcut: due rows are sorted by their earliest due date in memory; fine while a desk has tens due, page in SQL past ~500
            List<CancellationRowResponse> rows = rows(bookingRepository.findAll(spec, NEWEST), today).stream()
                    .sorted(Comparator.comparing((CancellationRowResponse r) -> r.getNextFollowUp() == null ? today : r.getNextFollowUp().getDueDate()))
                    .toList();
            int from = (int) Math.min(pageable.getOffset(), rows.size());
            Page<CancellationRowResponse> page = new PageImpl<>(rows.subList(from, Math.min(from + pageable.getPageSize(), rows.size())), pageable, rows.size());
            return PagedResponse.from(page, Function.identity());
        }
        Page<Booking> page = bookingRepository.findAll(spec, org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST));
        List<CancellationRowResponse> mapped = rows(page.getContent(), today);
        return PagedResponse.from(new PageImpl<>(mapped, page.getPageable(), page.getTotalElements()), Function.identity());
    }

    @Transactional(readOnly = true)
    public CancellationDetailResponse detail(String bookingId) {
        Booking b = bookingService.findAccessibleBooking(bookingId);
        List<CancellationFollowUp> followUps = followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc(bookingId);
        List<CancelRequestResponse> requests = b.getPlatformBookingId() == null ? List.of() : requestService.listForCrmBooking(b.getPlatformBookingId());
        CancelRequestStatus newest = requests.isEmpty() ? null : requests.get(requests.size() - 1).status();
        return CancellationDetailResponse.builder()
                .row(row(b, newest, followUps, today()))
                .followUps(followUps.stream().map(CancellationService::view).toList())
                .voyraRequests(requests)
                .activity(activityService.list(bookingId))
                .build();
    }

    /**
     * A cancellation scheduled for a later date needs a live booking with no Voyra request under way; a plain follow-up
     * needs the booking to have a cancellation to follow up (cancelled, a Voyra request, or a scheduled cancellation).
     */
    @Transactional
    public CancellationFollowUpResponse addFollowUp(String bookingId, CancellationFollowUpRequest r) {
        Booking b = bookingService.findAccessibleBooking(bookingId);
        LocalDate today = today();
        LocalDate due = dueDate(r.getDueDate(), r.getInDays(), today);
        boolean cancelled = b.getBookingStatus() == BookingStatus.CANCELLED;
        boolean requestActive = b.getPlatformBookingId() != null
                && requestRepository.existsByPlatformBookingIdAndStatusIn(b.getPlatformBookingId(), CancelRequestStatus.ACTIVE);
        List<CancellationFollowUp> existing = followUpRepository.findByBookingIdOrderByDueDateAscCreatedAtAsc(bookingId);
        if (r.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL && (cancelled || requestActive)) {
            throw new BookingConflictException("NOT_CANCELLABLE", "This booking is already cancelled or being cancelled", Map.of());
        }
        boolean hasRequest = b.getPlatformBookingId() != null
                && !requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getPlatformBookingId()).isEmpty();
        boolean onTab = (cancelled && !BookingService.isFailCancelled(b)) || hasRequest
                || existing.stream().anyMatch(f -> f.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL);
        if (r.getKind() == CancellationFollowUpKind.FOLLOW_UP && !onTab) {
            throw new BookingConflictException("NO_CANCELLATION", "Schedule or request a cancellation first", Map.of());
        }
        AuthorResolver.AuthorInfo by = authorResolver.resolveCurrentAuthor();
        String assignee = r.getAssignedAgentId() == null || r.getAssignedAgentId().isBlank() ? by.id() : r.getAssignedAgentId();
        CancellationFollowUp saved = followUpRepository.save(CancellationFollowUp.builder()
                .id(UniqueIdResolver.resolve(followUpRepository::existsById))
                .bookingId(bookingId).kind(r.getKind()).dueDate(due).note(blankToNull(r.getNote()))
                .assignedAgentId(assignee).assignedAgentName(assigneeName(assignee))
                .status(FollowUpStatus.OPEN).createdBy(by.id()).createdByName(by.name()).createdAt(LocalDateTime.now())
                .build());
        activityService.record(bookingId, r.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL ? "CANCELLATION_SCHEDULED" : "CANCELLATION_FOLLOW_UP",
                (r.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL ? "Cancellation scheduled for " : "Follow-up added for ")
                        + DAY.format(due) + ", " + saved.getAssignedAgentName() + withNote(saved.getNote()));
        return view(saved);
    }

    /** Moves, reassigns or re-notes an open follow-up. */
    @Transactional
    public CancellationFollowUpResponse updateFollowUp(String bookingId, String followUpId, CancellationFollowUpUpdateRequest r) {
        bookingService.findAccessibleBooking(bookingId);
        CancellationFollowUp f = openFollowUp(bookingId, followUpId);
        StringBuilder text = new StringBuilder();
        if (r.getDueDate() != null && !r.getDueDate().equals(f.getDueDate())) {
            f.setDueDate(dueDate(r.getDueDate(), null, today()));
            text.append("moved to ").append(DAY.format(f.getDueDate()));
        }
        if (r.getAssignedAgentId() != null && !r.getAssignedAgentId().isBlank() && !r.getAssignedAgentId().equals(f.getAssignedAgentId())) {
            f.setAssignedAgentName(assigneeName(r.getAssignedAgentId()));
            f.setAssignedAgentId(r.getAssignedAgentId());
            text.append(text.isEmpty() ? "" : ", ").append("given to ").append(f.getAssignedAgentName());
        }
        if (r.getNote() != null && !Objects.equals(blankToNull(r.getNote()), f.getNote())) {
            f.setNote(blankToNull(r.getNote()));
            text.append(text.isEmpty() ? "" : ", ").append("note changed").append(withNote(f.getNote()));
        }
        if (!text.isEmpty()) {
            activityService.record(bookingId, "CANCELLATION_FOLLOW_UP_CHANGED", label(f) + " " + text);
        }
        return view(followUpRepository.save(f));
    }

    /** Done is final: a scheduled cancellation closed without a cancel means the booking was kept. */
    @Transactional
    public CancellationFollowUpResponse completeFollowUp(String bookingId, String followUpId) {
        bookingService.findAccessibleBooking(bookingId);
        CancellationFollowUp f = openFollowUp(bookingId, followUpId);
        AuthorResolver.AuthorInfo by = authorResolver.resolveCurrentAuthor();
        f.setStatus(FollowUpStatus.DONE);
        f.setDoneBy(by.id());
        f.setDoneByName(by.name());
        f.setDoneAt(LocalDateTime.now());
        activityService.record(bookingId, "CANCELLATION_FOLLOW_UP_DONE", label(f) + " of " + DAY.format(f.getDueDate()) + " done");
        return view(followUpRepository.save(f));
    }

    // ---- rows

    private List<CancellationRowResponse> rows(List<Booking> bookings, LocalDate today) {
        List<String> ids = bookings.stream().map(Booking::getId).toList();
        Map<String, List<CancellationFollowUp>> followUps = ids.isEmpty() ? Map.of()
                : followUpRepository.findByBookingIdInOrderByDueDateAscCreatedAtAsc(ids).stream()
                        .collect(Collectors.groupingBy(CancellationFollowUp::getBookingId));
        List<String> platformIds = bookings.stream().map(Booking::getPlatformBookingId).filter(Objects::nonNull).toList();
        // ordered oldest first, so the last one per booking is its newest request
        Map<String, CancelRequestStatus> newest = platformIds.isEmpty() ? Map.of()
                : requestRepository.findByPlatformBookingIdInOrderByCreatedAtAsc(platformIds).stream()
                        .collect(Collectors.toMap(PlatformCancelRequest::getPlatformBookingId, PlatformCancelRequest::getStatus, (a, b) -> b));
        return bookings.stream()
                .map(b -> row(b, b.getPlatformBookingId() == null ? null : newest.get(b.getPlatformBookingId()),
                        followUps.getOrDefault(b.getId(), List.of()), today))
                .toList();
    }

    private static CancellationRowResponse row(Booking b, CancelRequestStatus newest, List<CancellationFollowUp> followUps, LocalDate today) {
        CancellationFollowUp next = followUps.stream().filter(f -> f.getStatus() == FollowUpStatus.OPEN)
                .min(Comparator.comparing(CancellationFollowUp::getDueDate)).orElse(null);
        return CancellationRowResponse.builder()
                .bookingId(b.getId()).bookingRef(BookingService.bookingRef(b.getBookingNumber())).type(b.getType())
                .clientName(b.getClientName()).destination(b.getDestination()).travelDate(b.getDepartureDate()).agentName(b.getAgentName())
                .status(CancellationStatusRules.of(b, newest, followUps, today))
                .followUpDue(CancellationStatusRules.followUpDue(followUps, today))
                .nextFollowUp(next == null ? null : view(next))
                .voyraRequestStatus(newest)
                .cancelledAt(b.getCancelledAt())
                .cancellationCharges(nz(b.getCancellationSupplierCharge()).add(nz(b.getCancellationAgencyFee())))
                .customerRefundDue(nz(b.getCustomerRefundDue())).refundedToClient(nz(b.getRefundedTotalInr()))
                .supplierRefundExpected(nz(b.getSupplierRefundExpected())).supplierRefundReceived(nz(b.getSupplierRefundReceived()))
                .crossChecked(b.getRefundCheckedAt() != null)
                .build();
    }

    private static CancellationFollowUpResponse view(CancellationFollowUp f) {
        return CancellationFollowUpResponse.builder()
                .id(f.getId()).kind(f.getKind()).dueDate(f.getDueDate()).note(f.getNote())
                .assignedAgentId(f.getAssignedAgentId()).assignedAgentName(f.getAssignedAgentName()).status(f.getStatus())
                .createdByName(f.getCreatedByName()).createdAt(f.getCreatedAt()).doneByName(f.getDoneByName()).doneAt(f.getDoneAt())
                .build();
    }

    // ---- helpers

    /** An agent sees the bookings he may open; the owner and the accountant see them all (null: no narrowing). */
    private Specification<Booking> scope() {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        if (!p.isAgent()) {
            return null;
        }
        Agent agent = agentRepository.findById(p.userId()).orElseThrow(() -> new IllegalStateException("Agent not found: " + p.userId()));
        return BookingSpecifications.accessibleToAgent(p.userId(), agent.getManageableServices());
    }

    private CancellationFollowUp openFollowUp(String bookingId, String followUpId) {
        CancellationFollowUp f = followUpRepository.findByIdAndBookingId(followUpId, bookingId)
                .orElseThrow(() -> new NotFoundException("Follow-up not found: " + followUpId));
        if (f.getStatus() != FollowUpStatus.OPEN) {
            throw new BookingConflictException("FOLLOW_UP_DONE", "This follow-up is already done", Map.of());
        }
        return f;
    }

    private static LocalDate dueDate(LocalDate date, Integer inDays, LocalDate today) {
        if ((date == null) == (inDays == null)) {
            throw new FieldValidationException("dueDate", "Pick a date, or after how many days");
        }
        LocalDate due = date != null ? date : today.plusDays(inDays);
        if (due.isBefore(today)) {
            throw new FieldValidationException("dueDate", "Pick today or a later date");
        }
        return due;
    }

    /** The owner (user id = agency id) or an active agent of this agency, else a 400. */
    private String assigneeName(String userId) {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        if (userId.equals(tenantId)) {
            return tenantRepository.findById(tenantId).map(Tenant::getOwnerName).orElseThrow();
        }
        return agentRepository.findByIdAndTenantId(userId, tenantId)
                .filter(a -> a.getUserRole() == UserType.AGENT && Boolean.TRUE.equals(a.getIsActive()))
                .map(Agent::getName)
                .orElseThrow(() -> new FieldValidationException("assignedAgentId", "Pick the owner or an active agent"));
    }

    private static String label(CancellationFollowUp f) {
        return f.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL ? "Scheduled cancellation" : "Follow-up";
    }

    private static String withNote(String note) {
        return note == null ? "" : ": " + note;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private LocalDate today() {
        return FinancialYear.dateOf(clock.instant());
    }
}
