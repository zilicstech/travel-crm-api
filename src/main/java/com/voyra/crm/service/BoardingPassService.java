package com.voyra.crm.service;

import com.voyra.crm.dto.BoardingPassRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.BoardingPass;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.enums.BoardingPassStatus;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BoardingPassRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.spec.CancellationSpecifications;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.booking.PlatformCancelRequestService;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.UniqueIdResolver;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Boarding passes (client round 2026-11-22): the agency's flights travelling on a day (tomorrow by default, India
 * time), and for each whether its boarding pass was generated (with an optional proof) or is not needed. A flight's
 * dates are the booking's own (outbound, return) and its ticket vouchers' (a return ticket may be its own voucher).
 */
@Service
@RequiredArgsConstructor
public class BoardingPassService {

    private static final Sort ORDER = Sort.by(Sort.Direction.ASC, "bookingNumber");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final String CATEGORY = "boarding-passes";
    private static final int MAX_REASON = 300;

    private final BookingRepository bookingRepository;
    private final BookingDocumentRepository documentRepository;
    private final BookingPassengerRepository passengerRepository;
    private final BoardingPassRepository repository;
    private final BookingService bookingService;
    private final BookingActivityService activityService;
    private final PlatformCancelRequestService requestService;
    private final AgentRepository agentRepository;
    private final AuthorResolver authorResolver;
    private final FileStorageService fileStorage;
    Clock clock = Clock.systemUTC();

    /** Content of a proof file, for the controller to stream. */
    public record ProofFile(Resource resource, String fileName, String contentType) {
    }

    @Transactional(readOnly = true)
    public PagedResponse<BoardingPassRowResponse> list(LocalDate date, boolean pendingOnly, Pageable pageable) {
        LocalDate day = date != null ? date : today().plusDays(1);
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        List<String> voucherBookings = documentRepository.findBookingIdsWithTicketOn(day.toString());
        Specification<Booking> spec = Specification.where(scope(p)).and(travelling(day, voucherBookings, day.isBefore(today())))
                .and(CancellationSpecifications.noActiveVoyraRequest(p.tenantId()));
        if (pendingOnly) {
            spec = spec.and(noBoardingPass(day));
        }
        Page<Booking> page = bookingRepository.findAll(spec, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), ORDER));
        Map<String, BoardingPass> passes = page.isEmpty() ? Map.of()
                : repository.findByBookingIdInAndTravelDate(page.getContent().stream().map(Booking::getId).toList(), day).stream()
                        .collect(Collectors.toMap(BoardingPass::getBookingId, Function.identity()));
        List<BoardingPassRowResponse> rows = new ArrayList<>();
        for (Booking b : page.getContent()) {
            rows.add(row(b, day, passes.get(b.getId())));
        }
        return PagedResponse.from(new PageImpl<>(rows, page.getPageable(), page.getTotalElements()), Function.identity());
    }

    /** Every flight date of one booking with its boarding pass, for the booking page. */
    @Transactional(readOnly = true)
    public List<BoardingPassRowResponse> forBooking(String bookingId) {
        Booking b = bookingService.findAccessibleBooking(bookingId);
        if (b.getType() != BookingType.FLIGHT) {
            return List.of();
        }
        Map<LocalDate, BoardingPass> passes = repository.findByBookingId(bookingId).stream()
                .collect(Collectors.toMap(BoardingPass::getTravelDate, Function.identity()));
        return flightDates(b).stream().sorted().map(d -> row(b, d, passes.get(d))).toList();
    }

    /** Marks the day's boarding pass generated (an optional proof file) or not needed (an optional reason). */
    @Transactional
    public BoardingPassRowResponse mark(String bookingId, LocalDate travelDate, BoardingPassStatus status, String reason, MultipartFile file) {
        Booking b = bookingService.findAccessibleBooking(bookingId);
        if (b.getType() != BookingType.FLIGHT || !flightDates(b).contains(travelDate)) {
            throw new FieldValidationException("travelDate", "This booking has no flight on that date");
        }
        String cleanReason = status == BoardingPassStatus.NOT_NEEDED && reason != null && !reason.isBlank() ? reason.trim() : null;
        if (cleanReason != null && cleanReason.length() > MAX_REASON) {
            throw new FieldValidationException("reason", "Write at most 300 characters");
        }
        boolean withFile = status == BoardingPassStatus.GENERATED && file != null && !file.isEmpty();
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = withFile ? fileStorage.store(tenantId, CATEGORY, bookingId, file) : null;
        AuthorResolver.AuthorInfo by = authorResolver.resolveCurrentAuthor();
        BoardingPass pass = repository.findByBookingIdAndTravelDate(bookingId, travelDate)
                .orElseGet(() -> BoardingPass.builder().id(UniqueIdResolver.resolve(repository::existsById)).bookingId(bookingId).travelDate(travelDate).build());
        String oldKey = pass.getFileKey();
        pass.setStatus(status);
        pass.setReason(cleanReason);
        pass.setFileKey(fileKey);
        pass.setFileName(withFile ? file.getOriginalFilename() : null);
        pass.setFileType(withFile ? file.getContentType() : null);
        pass.setUpdatedBy(by.id());
        pass.setUpdatedByName(by.name());
        pass.setUpdatedAt(LocalDateTime.now());
        repository.save(pass);
        deleteAfterCommit(oldKey);
        activityService.record(bookingId, status == BoardingPassStatus.GENERATED ? "BOARDING_PASS_GENERATED" : "BOARDING_PASS_NOT_NEEDED",
                (status == BoardingPassStatus.GENERATED ? "Boarding pass generated for " : "Boarding pass not needed for ") + DAY.format(travelDate)
                        + (withFile ? " (" + pass.getFileName() + ")" : "") + (cleanReason != null ? ": " + cleanReason : ""));
        return row(b, travelDate, pass);
    }

    /** Puts the day back to still to do, deleting its proof file after commit. */
    @Transactional
    public void reset(String boardingPassId) {
        BoardingPass pass = found(boardingPassId);
        repository.delete(pass);
        deleteAfterCommit(pass.getFileKey());
        activityService.record(pass.getBookingId(), "BOARDING_PASS_RESET", "Boarding pass for " + DAY.format(pass.getTravelDate()) + " set back to still to do");
    }

    @Transactional(readOnly = true)
    public ProofFile file(String boardingPassId) {
        BoardingPass pass = found(boardingPassId);
        if (pass.getFileKey() == null) {
            throw new NotFoundException("No file on this boarding pass");
        }
        return new ProofFile(fileStorage.retrieve(pass.getFileKey()), pass.getFileName(), pass.getFileType());
    }

    // ---- helpers

    /** Looks the row up through its booking, so a row of a booking the caller may not open is 404 like any unknown one. */
    private BoardingPass found(String boardingPassId) {
        BoardingPass pass = repository.findById(boardingPassId).orElseThrow(() -> new NotFoundException("Boarding pass not found: " + boardingPassId));
        bookingService.findAccessibleBooking(pass.getBookingId());
        return pass;
    }

    private BoardingPassRowResponse row(Booking b, LocalDate day, BoardingPass pass) {
        String leg = day.equals(b.getJourneyDate()) ? "ONWARD" : day.equals(b.getReturnDate()) ? "RETURN" : "FLIGHT";
        Set<Integer> cancelled = b.getPlatformBookingId() == null ? Set.of() : requestService.cancelledOn(b.getPlatformBookingId(), day);
        List<BookingPassenger> pax = passengerRepository.findByBookingIdOrderBySortOrderAsc(b.getId());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < pax.size(); i++) {
            // a Voyra booking's passengers are mirrored in the platform's order, so positions match its requests
            if (!cancelled.contains(i)) names.add(pax.get(i).getPassengerName());
        }
        return BoardingPassRowResponse.builder()
                .bookingId(b.getId()).bookingRef(BookingService.bookingRef(b.getBookingNumber())).travelDate(day).leg(leg)
                .destination(b.getDestination()).pnr(b.getPnr()).clientName(b.getClientName()).agentName(b.getAgentName()).passengers(names)
                .status(pass == null ? null : pass.getStatus()).reason(pass == null ? null : pass.getReason())
                .boardingPassId(pass == null ? null : pass.getId()).fileName(pass == null ? null : pass.getFileName())
                .updatedByName(pass == null ? null : pass.getUpdatedByName()).updatedAt(pass == null ? null : pass.getUpdatedAt())
                .build();
    }

    /** The booking's own flight dates and its ticket vouchers' start and return dates. */
    private Set<LocalDate> flightDates(Booking b) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (b.getJourneyDate() != null) dates.add(b.getJourneyDate());
        if (b.getReturnDate() != null) dates.add(b.getReturnDate());
        for (BookingDocument d : documentRepository.findByBookingIdOrderBySortOrderAsc(b.getId())) {
            if (d.getDocType() == BookingDocumentType.E_TICKET && d.getDetails() != null) {
                for (String key : List.of("journeyDate", "returnDate")) {
                    String v = d.getDetails().get(key);
                    if (v != null) dates.add(LocalDate.parse(v));
                }
            }
        }
        return dates;
    }

    /** A flight booking travelling on the day; COMPLETED counts too for a past day (the nightly job completes trips). */
    static Specification<Booking> travelling(LocalDate day, List<String> voucherBookings, boolean past) {
        return (root, query, cb) -> {
            List<Predicate> on = new ArrayList<>(List.of(cb.equal(root.get("journeyDate"), day), cb.equal(root.get("returnDate"), day)));
            if (!voucherBookings.isEmpty()) {
                on.add(root.get("id").in(voucherBookings));
            }
            return cb.and(cb.equal(root.get("type"), BookingType.FLIGHT),
                    root.get("bookingStatus").in(past ? List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED) : List.of(BookingStatus.CONFIRMED)),
                    cb.or(on.toArray(new Predicate[0])));
        };
    }

    static Specification<Booking> noBoardingPass(LocalDate day) {
        return (root, query, cb) -> {
            Subquery<String> sq = query.subquery(String.class);
            Root<BoardingPass> bp = sq.from(BoardingPass.class);
            sq.select(bp.get("id")).where(cb.equal(bp.get("bookingId"), root.get("id")), cb.equal(bp.get("travelDate"), day));
            return cb.not(cb.exists(sq));
        };
    }

    /** An agent sees the bookings he may open (null: no narrowing for the owner). */
    private Specification<Booking> scope(CustomUserPrincipal p) {
        if (!p.isAgent()) {
            return null;
        }
        Agent agent = agentRepository.findById(p.userId()).orElseThrow(() -> new IllegalStateException("Agent not found: " + p.userId()));
        return com.voyra.crm.repository.spec.BookingSpecifications.accessibleToAgent(p.userId(), agent.getManageableServices());
    }

    private void deleteAfterCommit(String fileKey) {
        if (fileKey == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorage.delete(fileKey);
            }
        });
    }

    private LocalDate today() {
        return FinancialYear.dateOf(clock.instant());
    }
}
