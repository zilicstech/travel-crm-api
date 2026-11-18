package com.voyra.crm.service.booking;

import com.voyra.crm.dto.AdminCancelRequestResponse;
import com.voyra.crm.dto.CancelRequestCompleteRequest;
import com.voyra.crm.dto.CancelRequestCreateRequest;
import com.voyra.crm.dto.CancelRequestResponse;
import com.voyra.crm.dto.CancelSectorResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.entity.PlatformCancelRequestFile;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.CancelReason;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformCancelRequestFileRepository;
import com.voyra.crm.repository.PlatformCancelRequestRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.FileStorageService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.UploadValidator;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cancellation by request (owner ruling 2026-11-18): an agency asks Voyra to cancel some passengers and sectors of a
 * flight, or a whole stay, with a reason, remarks and proof; a platform admin reviews it, cancels at the supplier by hand
 * and completes it here, possibly with another reason, selection and figures. No supplier call is made.
 *
 * <p>Every transition takes the booking's row lock, then the request's, and checks the request's current status, so a
 * withdraw and a completion, or two completions, never both apply. Completing stores the figures first (COMPLETING),
 * posts the money under keys of this request ({@code pb-refund:<booking>:<request>}), then marks it COMPLETED; a retry
 * with other figures is refused, so what the wallet holds is what the request says. Once every passenger is cancelled
 * on every sector the booking is CANCELLED and its CRM mirror follows.
 */
@Service
public class PlatformCancelRequestService {

    static final int MAX_FILES = 5;
    static final long MAX_FILES_BYTES = 10L * 1024 * 1024;
    private static final Set<String> FILE_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "webp");
    private static final String REF_TYPE = "CANCEL_REQUEST";
    static final String CANCELLED_ANOTHER_WAY = "This booking was cancelled another way";

    private final PlatformCancelRequestRepository requestRepository;
    private final PlatformCancelRequestFileRepository fileRepository;
    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingService bookingService;
    private final PlatformBookingQueryService queryService;
    private final PlatformBookingEventRecorder events;
    private final PaxCodec paxCodec;
    private final FileStorageService fileStorage;
    private final TenantRepository tenantRepository;
    private final com.voyra.crm.repository.AgentRepository agentRepository;
    private final PlatformAuditService audit;
    private final NotificationService notifications;
    private final PlatformSettingService settings;
    private final TransactionTemplate tx;
    /** Today in India for the urgent flag; tests move it. */
    Clock clock = Clock.systemUTC();

    public PlatformCancelRequestService(PlatformCancelRequestRepository requestRepository, PlatformCancelRequestFileRepository fileRepository,
                                        PlatformBookingRepository bookingRepository, PlatformBookingService bookingService,
                                        PlatformBookingQueryService queryService, PlatformBookingEventRecorder events, PaxCodec paxCodec,
                                        FileStorageService fileStorage, TenantRepository tenantRepository,
                                        com.voyra.crm.repository.AgentRepository agentRepository, PlatformAuditService audit,
                                        NotificationService notifications, PlatformSettingService settings,
                                        PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.fileRepository = fileRepository;
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.queryService = queryService;
        this.events = events;
        this.paxCodec = paxCodec;
        this.fileStorage = fileStorage;
        this.tenantRepository = tenantRepository;
        this.agentRepository = agentRepository;
        this.audit = audit;
        this.notifications = notifications;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---- agency

    /** Raises a request on a ticketed booking: one active request per booking, the selection checked, proof stored. */
    public CancelRequestResponse create(String bookingId, CancelRequestCreateRequest req, List<MultipartFile> files) {
        CustomUserPrincipal p = bookingService.requireBooker();
        PlatformBooking seen = bookingService.actedOn(p, bookingRepository.findByIdAndTenantId(bookingId, p.tenantId()));
        requireFits(req.reason(), seen.getProduct());
        if (req.reason().needsRemarks() && (req.remarks() == null || req.remarks().isBlank())) {
            throw new BookingRequestException("REMARKS_REQUIRED", "Tell Voyra more about this reason in the remarks");
        }
        List<MultipartFile> proof = checkFiles(files);
        String name = requesterName(p);
        PlatformCancelRequest saved = tx.execute(s -> {
            PlatformBooking b = lockBooking(bookingId);
            if (b.getStatus() != PlatformBookingStatus.TICKETED) {
                throw new BookingConflictException("NOT_CANCELLABLE", "Only a confirmed booking can be cancelled", Map.of());
            }
            if (requestRepository.existsByPlatformBookingIdAndStatusIn(bookingId, CancelRequestStatus.ACTIVE)) {
                throw new BookingConflictException("CANCEL_REQUEST_OPEN", "A cancellation request for this booking is already with Voyra", Map.of());
            }
            Selection sel = selection(b, req.passengers(), req.sectors());
            Instant now = clock.instant();
            PlatformCancelRequest r = requestRepository.saveAndFlush(PlatformCancelRequest.builder()
                    .id(UniqueIdResolver.resolve(requestRepository::existsById)).platformBookingId(bookingId).tenantId(b.getTenantId())
                    .product(b.getProduct()).status(CancelRequestStatus.OPEN).reason(req.reason())
                    .passengers(sel.passengers()).sectors(sel.sectors())
                    .remarks(req.remarks() == null || req.remarks().isBlank() ? null : req.remarks().trim())
                    .requestedBy(p.userId()).requestedByName(name).createdAt(now).updatedAt(now).build());
            for (MultipartFile f : proof) {
                String fileId = UniqueIdResolver.resolve(fileRepository::existsById);
                String key = fileStorage.store("platform", "cancel-requests/" + b.getTenantId(), r.getId(), f);
                String fileName = f.getOriginalFilename();
                fileRepository.save(PlatformCancelRequestFile.builder().id(fileId).requestId(r.getId()).fileKey(key)
                        .fileName(fileName.length() <= 255 ? fileName : fileName.substring(fileName.length() - 255))
                        .contentType(contentType(fileName)).sizeBytes(f.getSize()).createdAt(now).build());
            }
            events.record(bookingId, "CANCEL_REQUESTED", Map.of("requestId", r.getId(), "reason", r.getReason().name()));
            notifications.cancelRequestRaised(b, r, tenantRepository.findById(b.getTenantId()).map(Tenant::getAgencyName).orElse(b.getTenantId()));
            return r;
        });
        return views(List.of(saved), seen).get(0);
    }

    /** The booking's requests, newest last, for anyone who may open the booking (the accountant reads them too). */
    public List<CancelRequestResponse> list(String bookingId) {
        PlatformBooking b = queryService.visible(bookingId);
        return views(requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc(bookingId), b);
    }

    /** The agency takes back a request Voyra has not picked up yet; once picked up it is Voyra's to finish. */
    public CancelRequestResponse withdraw(String bookingId, String requestId) {
        CustomUserPrincipal p = bookingService.requireBooker();
        PlatformBooking seen = bookingService.actedOn(p, bookingRepository.findByIdAndTenantId(bookingId, p.tenantId()));
        PlatformCancelRequest r = tx.execute(s -> {
            lockBooking(bookingId);
            PlatformCancelRequest locked = lockRequest(requestId, bookingId);
            if (locked.getStatus() == CancelRequestStatus.WITHDRAWN) {
                return locked;
            }
            if (locked.getStatus() != CancelRequestStatus.OPEN) {
                throw new BookingConflictException("CANCEL_REQUEST_LOCKED", "Voyra is already working on this request", Map.of());
            }
            finish(locked, CancelRequestStatus.WITHDRAWN);
            events.record(bookingId, "CANCEL_REQUEST_WITHDRAWN", Map.of("requestId", requestId));
            return requestRepository.save(locked);
        });
        return views(List.of(r), seen).get(0);
    }

    /** A proof file of a request on a booking the caller may open; any mismatch is 404. */
    public FileContent agencyFile(String bookingId, String requestId, String fileId) {
        queryService.visible(bookingId);
        lookup(requestId, bookingId);
        return content(fileId, requestId);
    }

    // ---- platform admin

    /** Active requests oldest first by default; finished ones newest first. */
    public PagedResponse<AdminCancelRequestResponse> adminList(CancelRequestStatus status, Integer page, Integer size) {
        Set<CancelRequestStatus> statuses = status == null ? CancelRequestStatus.ACTIVE : Set.of(status);
        Sort sort = CancelRequestStatus.ACTIVE.containsAll(statuses) ? Sort.by("createdAt") : Sort.by(Sort.Direction.DESC, "createdAt");
        var pageable = com.voyra.crm.util.PageRequestUtil.resolve(page == null ? 0 : page, size);
        Page<PlatformCancelRequest> rows = requestRepository.findByStatusIn(statuses, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
        Map<String, PlatformBooking> bookings = bookingRepository.findAllById(rows.getContent().stream().map(PlatformCancelRequest::getPlatformBookingId).distinct().toList())
                .stream().collect(Collectors.toMap(PlatformBooking::getId, Function.identity()));
        Map<String, String> agencies = agencyNames(rows.getContent());
        List<AdminCancelRequestResponse> content = rows.getContent().stream()
                .map(r -> adminView(r, bookings.get(r.getPlatformBookingId()), agencies.get(r.getTenantId()), false)).toList();
        return PagedResponse.<AdminCancelRequestResponse>builder().content(content).page(rows.getNumber()).size(rows.getSize())
                .totalElements(rows.getTotalElements()).totalPages(rows.getTotalPages()).build();
    }

    public AdminCancelRequestResponse adminGet(String requestId) {
        PlatformCancelRequest r = requestRepository.findById(requestId).orElseThrow(() -> new NotFoundException("Cancellation request not found"));
        PlatformBooking b = bookingRepository.findById(r.getPlatformBookingId()).orElseThrow(() -> new NotFoundException("Booking not found"));
        return adminView(r, b, agencyNames(List.of(r)).get(r.getTenantId()), true);
    }

    /** Picks the request up, so the agency can no longer withdraw it. */
    public AdminCancelRequestResponse start(String requestId) {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        String bookingId = bookingIdOf(requestId);
        tx.executeWithoutResult(s -> {
            lockBooking(bookingId);
            PlatformCancelRequest r = lockRequest(requestId, bookingId);
            if (r.getStatus() == CancelRequestStatus.IN_PROGRESS) {
                return;
            }
            requireStatus(r, CancelRequestStatus.OPEN);
            r.setStatus(CancelRequestStatus.IN_PROGRESS);
            r.setStartedBy(p.userId());
            r.setStartedAt(clock.instant());
            r.setUpdatedAt(clock.instant());
            requestRepository.save(r);
            events.record(bookingId, "CANCEL_REQUEST_STARTED", Map.of("requestId", requestId));
            audit.record(PlatformAuditAction.CANCEL_REQUEST_START, REF_TYPE, requestId, Map.of("bookingId", bookingId));
        });
        return adminGet(requestId);
    }

    /** Declines the request with a note for the agency; nothing changes on the booking. */
    public AdminCancelRequestResponse reject(String requestId, String note) {
        String bookingId = bookingIdOf(requestId);
        tx.executeWithoutResult(s -> {
            PlatformBooking b = lockBooking(bookingId);
            PlatformCancelRequest r = lockRequest(requestId, bookingId);
            if (r.getStatus() == CancelRequestStatus.REJECTED) {
                return;
            }
            requireStatus(r, CancelRequestStatus.OPEN, CancelRequestStatus.IN_PROGRESS);
            r.setAdminNote(note.trim());
            finish(r, CancelRequestStatus.REJECTED);
            requestRepository.save(r);
            events.record(bookingId, "CANCEL_REQUEST_REJECTED", Map.of("requestId", requestId));
            audit.record(PlatformAuditAction.CANCEL_REQUEST_REJECT, REF_TYPE, requestId, Map.of("bookingId", bookingId));
            notifications.cancelRequestRejected(b, r);
        });
        return adminGet(requestId);
    }

    /**
     * Records what Voyra cancelled at the supplier and posts the money: refund = supplier refund less Voyra's fee, at most
     * what the agency paid less refunds already posted, and above the chosen passengers' share only when confirmed;
     * commission taken back at most what is still unrecalled. A booking no longer ticketed (cancelled another way) gets
     * the request declined instead, with nothing posted.
     */
    public AdminCancelRequestResponse complete(String requestId, CancelRequestCompleteRequest req) {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        String bookingId = bookingIdOf(requestId);
        PlatformCancelRequest staged = tx.execute(s -> stage(bookingId, requestId, req));
        if (staged.getStatus() != CancelRequestStatus.COMPLETING) {
            return adminGet(requestId); // already completed, or declined because the booking moved on
        }
        PlatformBooking forMoney = bookingRepository.findById(bookingId).orElseThrow();
        boolean posted = bookingService.postCancelMoney(forMoney, staged.getRefund(), staged.getRecall(), ":" + requestId);
        PlatformBooking settled = tx.execute(s -> settle(bookingId, requestId, !posted, p.userId()));
        if (settled != null) {
            bookingService.scheduleMirror(settled);
        }
        return adminGet(requestId);
    }

    /** Step 1: validates and stores the figures (COMPLETING), or replays a retry of the same figures. */
    private PlatformCancelRequest stage(String bookingId, String requestId, CancelRequestCompleteRequest req) {
        PlatformBooking b = lockBooking(bookingId);
        PlatformCancelRequest r = lockRequest(requestId, bookingId);
        if (r.getStatus() == CancelRequestStatus.COMPLETED || r.getStatus() == CancelRequestStatus.REJECTED) {
            return r;
        }
        requireStatus(r, CancelRequestStatus.OPEN, CancelRequestStatus.IN_PROGRESS, CancelRequestStatus.COMPLETING);
        if (b.getStatus() != PlatformBookingStatus.TICKETED && r.getStatus() != CancelRequestStatus.COMPLETING) {
            r.setAdminNote(CANCELLED_ANOTHER_WAY);
            finish(r, CancelRequestStatus.REJECTED);
            events.record(bookingId, "CANCEL_REQUEST_REJECTED", Map.of("requestId", requestId, "cancelledAnotherWay", true));
            return requestRepository.save(r);
        }
        requireFits(req.finalReason(), b.getProduct());
        Selection sel = selection(b, req.passengers(), req.sectors());
        BigDecimal refund = req.supplierRefund().subtract(req.voyraFee()).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal recall = req.recall().setScale(2, RoundingMode.HALF_UP);
        if (r.getStatus() == CancelRequestStatus.COMPLETING) {
            boolean same = refund.compareTo(r.getRefund()) == 0 && recall.compareTo(r.getRecall()) == 0
                    && req.finalReason() == r.getFinalReason() && sel.passengers().equals(r.getPassengers()) && sel.sectors().equals(r.getSectors());
            if (!same) {
                throw new BookingConflictException("FIGURES_LOCKED",
                        "This request is already being completed with other figures; complete it with the same ones", Map.of());
            }
            return r;
        }
        Caps caps = caps(b, sel);
        if (refund.compareTo(caps.hardCap()) > 0) {
            throw new BookingRequestException("REFUND_TOO_HIGH", "The refund can be at most ₹" + caps.hardCap() + " (what the agency paid less refunds already given)");
        }
        if (refund.compareTo(caps.softCap()) > 0 && !req.exceedsShare()) {
            throw new BookingConflictException("EXCEEDS_SHARE", "The refund is above the chosen passengers' share of the fare (₹"
                    + caps.softCap() + "); confirm to go above it", Map.of("softCap", caps.softCap()));
        }
        if (recall.compareTo(caps.remainingRecall()) > 0) {
            throw new BookingRequestException("RECALL_TOO_HIGH", "At most ₹" + caps.remainingRecall() + " of commission is left to take back");
        }
        r.setFinalReason(req.finalReason());
        r.setPassengers(sel.passengers());
        r.setSectors(sel.sectors());
        r.setSupplierRefund(req.supplierRefund().setScale(2, RoundingMode.HALF_UP));
        r.setCharges(req.charges().setScale(2, RoundingMode.HALF_UP));
        r.setVoyraFee(req.voyraFee().setScale(2, RoundingMode.HALF_UP));
        r.setRefund(refund);
        r.setRecall(recall);
        r.setExceedsShare(refund.compareTo(caps.softCap()) > 0);
        r.setSupplierReference(blankToNull(req.supplierReference()));
        r.setAdminNote(blankToNull(req.note()));
        r.setStatus(CancelRequestStatus.COMPLETING);
        r.setUpdatedAt(clock.instant());
        return requestRepository.save(r);
    }

    /** Step 3: marks the request COMPLETED and, once nothing is left, the booking CANCELLED; the booking to mirror, or null on a replay. */
    private PlatformBooking settle(String bookingId, String requestId, boolean offline, String by) {
        PlatformBooking b = lockBooking(bookingId);
        PlatformCancelRequest r = lockRequest(requestId, bookingId);
        if (r.getStatus() != CancelRequestStatus.COMPLETING) {
            return null;
        }
        r.setOffline(offline);
        r.setCompletedBy(by);
        r.setCompletedAt(clock.instant());
        finish(r, CancelRequestStatus.COMPLETED);
        requestRepository.save(r);
        List<PlatformCancelRequest> all = requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc(bookingId);
        boolean whole = CancelParts.whole(CancelParts.cancelled(all), paxTypes(b).size(), CancelParts.sectors(b));
        BigDecimal total = all.stream().filter(x -> x.getStatus() == CancelRequestStatus.COMPLETED).map(PlatformCancelRequest::getRefund)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("refund", r.getRefund());
        detail.put("recall", r.getRecall());
        detail.put("whole", whole);
        if (offline) {
            detail.put("offline", true);
        }
        events.record(bookingId, "CANCEL_REQUEST_COMPLETED", detail);
        if (whole) {
            b.setStatus(PlatformBookingStatus.CANCELLED);
            b.setCancelRefund(total);
            b.setCancelledAt(clock.instant());
            events.record(bookingId, "CANCELLED", Map.of("refund", total, "byRequest", requestId));
        }
        bookingService.queueMirror(b); // a partial cancellation is mirrored too: the CRM records its refund
        Map<String, Object> auditDetail = new LinkedHashMap<>(detail);
        auditDetail.put("bookingId", bookingId);
        auditDetail.put("supplierRefund", r.getSupplierRefund());
        auditDetail.put("voyraFee", r.getVoyraFee());
        auditDetail.put("finalReason", r.getFinalReason().name());
        audit.record(PlatformAuditAction.CANCEL_REQUEST_COMPLETE, REF_TYPE, requestId, auditDetail);
        notifications.cancelRequestCompleted(b, r, total);
        return bookingRepository.save(b);
    }

    public FileContent adminFile(String requestId, String fileId) {
        PlatformCancelRequest r = requestRepository.findById(requestId).orElseThrow(() -> new NotFoundException("Cancellation request not found"));
        FileContent c = content(fileId, r.getId());
        audit.record(PlatformAuditAction.CANCEL_REQUEST_FILE_VIEW, REF_TYPE, requestId, Map.of("fileId", fileId));
        return c;
    }

    /**
     * DPDP: remarks, Voyra's note and proof files of requests finished before {@code finishedBefore}. Each request is
     * cleared in its own transaction (keys nulled there), its files deleted from storage after that commits; a failed
     * delete is logged and leaves only an orphan object. Returns how many requests were cleared.
     */
    public int purgePersonalData(Instant finishedBefore) {
        int cleared = 0;
        for (PlatformCancelRequest due : requestRepository.findByFinishedAtBeforeAndPersonalDataPurgedAtIsNull(finishedBefore)) {
            List<String> keys = tx.execute(s -> {
                PlatformCancelRequest r = requestRepository.findByIdForUpdate(due.getId()).orElseThrow();
                List<PlatformCancelRequestFile> files = fileRepository.findByRequestIdInOrderByCreatedAtAsc(List.of(r.getId()));
                List<String> out = files.stream().map(PlatformCancelRequestFile::getFileKey).filter(Objects::nonNull).toList();
                files.forEach(f -> f.setFileKey(null));
                fileRepository.saveAll(files);
                r.setRemarks(null);
                r.setAdminNote(null);
                r.setPersonalDataPurgedAt(clock.instant());
                requestRepository.save(r);
                return out;
            });
            for (String key : keys) {
                try {
                    fileStorage.delete(key);
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(PlatformCancelRequestService.class)
                            .warn("Proof file of cancellation request {} not deleted: {}", due.getId(), e.getClass().getSimpleName());
                }
            }
            cleared++;
        }
        return cleared;
    }

    // ---- shared

    /** Not a DTO - unpacked into a binary response by the controller. */
    public record FileContent(Resource resource, String fileName, String contentType) {
    }

    private record Selection(List<Integer> passengers, List<Map<String, String>> sectors) {
    }

    private record Caps(BigDecimal share, BigDecimal softCap, BigDecimal hardCap, BigDecimal remainingRecall, BigDecimal recall) {
    }

    /** A hotel is the whole stay; a flight's choice is checked against the booking and what is already cancelled. */
    private Selection selection(PlatformBooking b, List<Integer> passengers, List<String> sectorKeys) {
        List<String> types = paxTypes(b);
        if (b.getProduct() == PricingProduct.HOTEL) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                all.add(i);
            }
            return new Selection(all, List.of());
        }
        List<CancelParts.Sector> sectors = CancelParts.sectors(b);
        List<String> keys = sectorKeys == null ? List.of() : sectorKeys;
        CancelParts.validate(passengers, keys, types, sectors,
                CancelParts.cancelled(requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId())));
        List<Map<String, String>> chosen = sectors.stream().filter(s -> keys.contains(s.key()))
                .map(s -> Map.of("key", s.key(), "label", s.label())).toList();
        return new Selection(passengers.stream().sorted().toList(), chosen);
    }

    /** Share of the fare, refund caps and commission left, counting completed requests (the one being completed is not one yet). */
    private Caps caps(PlatformBooking b, Selection sel) {
        List<PlatformCancelRequest> done = requestRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId()).stream()
                .filter(x -> x.getStatus() == CancelRequestStatus.COMPLETED).toList();
        BigDecimal refunded = done.stream().map(PlatformCancelRequest::getRefund).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recalled = done.stream().map(PlatformCancelRequest::getRecall).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> types = paxTypes(b);
        List<CancelParts.Sector> sectors = CancelParts.sectors(b);
        List<String> keys = CancelParts.keys(sel.sectors());
        BigDecimal share = CancelParts.share(b, sel.passengers(), keys, types, sectors.size());
        BigDecimal displayed = b.getDisplayed() == null ? BigDecimal.ZERO : b.getDisplayed();
        BigDecimal hardCap = displayed.subtract(refunded).max(BigDecimal.ZERO);
        BigDecimal softCap = displayed.multiply(share).setScale(2, RoundingMode.HALF_UP).min(hardCap);
        BigDecimal remaining = PlatformBookingService.recallOf(b).subtract(recalled).max(BigDecimal.ZERO);
        java.util.Set<String> after = new java.util.HashSet<>(CancelParts.cancelled(done));
        after.addAll(CancelParts.pairs(sel.passengers(), keys));
        BigDecimal recall = CancelParts.whole(after, types.size(), sectors) ? remaining
                : PlatformBookingService.recallOf(b).multiply(share).setScale(2, RoundingMode.HALF_UP).min(remaining);
        return new Caps(share.setScale(4, RoundingMode.HALF_UP), softCap, hardCap, remaining, recall);
    }

    /** Each passenger's type in booked order; a hotel whose passengers were cleared counts its booked heads as adults. */
    @SuppressWarnings("unchecked")
    private List<String> paxTypes(PlatformBooking b) {
        List<Map<String, Object>> pax = passengers(b);
        if (pax.isEmpty() && b.getPaxCount() != null) {
            return java.util.Collections.nCopies(b.getPaxCount(), "ADULT");
        }
        return pax.stream().map(m -> String.valueOf(m.get("type"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> passengers(PlatformBooking b) {
        if (b.getPaxEnc() == null) {
            return List.of();
        }
        Object list = paxCodec.decrypt(b.getPaxEnc(), Map.class).get("passengers");
        return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static void requireFits(CancelReason reason, PricingProduct product) {
        if (reason.product() != product) {
            throw new BookingRequestException("INVALID_REASON", "Choose a reason for a " + product.name().toLowerCase(Locale.ROOT) + " booking");
        }
    }

    private static List<MultipartFile> checkFiles(List<MultipartFile> files) {
        List<MultipartFile> proof = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (proof.size() > MAX_FILES) {
            throw new BookingRequestException("TOO_MANY_FILES", "Attach at most " + MAX_FILES + " files");
        }
        if (proof.stream().mapToLong(MultipartFile::getSize).sum() > MAX_FILES_BYTES) {
            throw new BookingRequestException("FILES_TOO_LARGE", "Attach at most 10 MB of files in all");
        }
        for (MultipartFile f : proof) {
            String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
            if (!FILE_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))) {
                throw new BookingRequestException("FILE_TYPE", "Attach PDF, JPG, PNG or WEBP files");
            }
            try {
                UploadValidator.assertAllowed(f, MAX_FILES_BYTES);
            } catch (IllegalArgumentException e) {
                throw new BookingRequestException("FILE_TYPE", "Attach PDF, JPG, PNG or WEBP files");
            }
        }
        return proof;
    }

    /** The owner's or the agent's name, as bookings show who booked. */
    private String requesterName(CustomUserPrincipal p) {
        String name = p.userId().equals(p.tenantId())
                ? tenantRepository.findById(p.tenantId()).map(Tenant::getOwnerName).orElse(null)
                : agentRepository.findById(p.userId()).map(com.voyra.crm.entity.Agent::getName).orElse(null);
        return name == null || name.isBlank() ? p.username() : name;
    }

    private PlatformBooking lockBooking(String bookingId) {
        return bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
    }

    private PlatformCancelRequest lockRequest(String requestId, String bookingId) {
        return requestRepository.findByIdForUpdate(requestId).filter(r -> r.getPlatformBookingId().equals(bookingId))
                .orElseThrow(() -> new NotFoundException("Cancellation request not found"));
    }

    private PlatformCancelRequest lookup(String requestId, String bookingId) {
        return requestRepository.findById(requestId).filter(r -> r.getPlatformBookingId().equals(bookingId))
                .orElseThrow(() -> new NotFoundException("Cancellation request not found"));
    }

    private String bookingIdOf(String requestId) {
        return requestRepository.findById(requestId).map(PlatformCancelRequest::getPlatformBookingId)
                .orElseThrow(() -> new NotFoundException("Cancellation request not found"));
    }

    private static void requireStatus(PlatformCancelRequest r, CancelRequestStatus... allowed) {
        if (!EnumSet.of(allowed[0], allowed).contains(r.getStatus())) {
            throw new BookingConflictException("CANCEL_REQUEST_CLOSED", "This request is already " + r.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' '), Map.of());
        }
    }

    private void finish(PlatformCancelRequest r, CancelRequestStatus status) {
        r.setStatus(status);
        r.setFinishedAt(clock.instant());
        r.setUpdatedAt(clock.instant());
    }

    private FileContent content(String fileId, String requestId) {
        PlatformCancelRequestFile f = fileRepository.findByIdAndRequestId(fileId, requestId)
                .filter(x -> x.getFileKey() != null).orElseThrow(() -> new NotFoundException("File not found"));
        return new FileContent(fileStorage.retrieve(f.getFileKey()), f.getFileName(), contentType(f.getFileName()));
    }

    /** By extension, never the uploader's claim, so a proof cannot be served as HTML. */
    private static String contentType(String fileName) {
        return MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<String, String> agencyNames(List<PlatformCancelRequest> rows) {
        Map<String, String> out = new java.util.HashMap<>();
        tenantRepository.findAllById(rows.stream().map(PlatformCancelRequest::getTenantId).distinct().toList())
                .forEach(t -> out.put(t.getId(), t.getAgencyName()));
        return out;
    }

    // ---- views

    private List<CancelRequestResponse> views(List<PlatformCancelRequest> rows, PlatformBooking b) {
        List<Map<String, Object>> pax = passengers(b);
        Map<String, List<PlatformCancelRequestFile>> files = fileRepository.findByRequestIdInOrderByCreatedAtAsc(rows.stream().map(PlatformCancelRequest::getId).toList())
                .stream().collect(Collectors.groupingBy(PlatformCancelRequestFile::getRequestId));
        Map<String, String> sectorDates = CancelParts.sectors(b).stream().filter(s -> s.date() != null)
                .collect(Collectors.toMap(CancelParts.Sector::key, CancelParts.Sector::date));
        return rows.stream().map(r -> {
            boolean done = r.getStatus() == CancelRequestStatus.COMPLETED;
            List<CancelRequestResponse.Passenger> people = r.getPassengers().stream().map(i -> {
                Map<String, Object> m = i < pax.size() ? pax.get(i) : Map.of();
                return new CancelRequestResponse.Passenger(i, m.isEmpty() ? null
                        : PlatformBookingQueryService.fullName(m.get("title"), m.get("firstName"), m.get("lastName")),
                        m.isEmpty() ? null : String.valueOf(m.get("type")));
            }).toList();
            return new CancelRequestResponse(r.getId(), r.getStatus(), r.getReason(), r.getFinalReason(),
                    r.getFinalReason() != null && r.getFinalReason() != r.getReason(), people,
                    r.getSectors().stream().map(s -> new CancelSectorResponse(s.get("key"), s.get("label"), sectorDates.get(s.get("key")))).toList(),
                    r.getRemarks(),
                    files.getOrDefault(r.getId(), List.of()).stream()
                            .map(f -> new CancelRequestResponse.FileRef(f.getId(), f.getFileKey() == null ? null : f.getFileName())).toList(),
                    done ? r.getRefund() : null, done ? r.getCharges() : null, done ? r.getRecall() : null, done && r.isOffline(),
                    r.getStatus() == CancelRequestStatus.COMPLETED || r.getStatus() == CancelRequestStatus.REJECTED ? r.getAdminNote() : null,
                    r.getRequestedByName(), r.getCreatedAt(), r.getCompletedAt());
        }).toList();
    }

    private AdminCancelRequestResponse adminView(PlatformCancelRequest r, PlatformBooking b, String agencyName, boolean detail) {
        CancelRequestResponse view = views(List.of(r), b).get(0);
        String travelDate = travelDate(b);
        LocalDate today = FinancialYear.dateOf(clock.instant());
        boolean urgent = travelDate != null && CancelRequestStatus.ACTIVE.contains(r.getStatus())
                && !LocalDate.parse(travelDate).isAfter(today.plusDays(1));
        AdminCancelRequestResponse.Prefill prefill = null;
        if (detail && CancelRequestStatus.ACTIVE.contains(r.getStatus()) && b.getStatus() == PlatformBookingStatus.TICKETED) {
            Caps caps = caps(b, new Selection(r.getPassengers(), r.getSectors()));
            prefill = new AdminCancelRequestResponse.Prefill(caps.share(), caps.softCap(), caps.hardCap(), caps.remainingRecall(),
                    caps.recall(), settings.voyraCancelFee());
        }
        return new AdminCancelRequestResponse(view, b.getId(), b.getProduct().name(), r.getTenantId(), agencyName, b.getPnr(), travelDate,
                urgent, r.getSupplierRefund(), r.getVoyraFee(), r.getSupplierReference(), r.isExceedsShare(), r.getStartedAt(), prefill,
                detail ? queryService.adminView(b) : null);
    }

    /** First departure or check-in (yyyy-MM-dd), from the review stamp or the stay; null when neither is readable. */
    @SuppressWarnings("unchecked")
    public static String travelDate(PlatformBooking b) {
        Object stamped = b.getPricingSnapshot() == null ? null : b.getPricingSnapshot().get("travelDate");
        Object checkIn = b.getItinerary() != null && b.getItinerary().get("stay") instanceof Map<?, ?> stay ? ((Map<String, Object>) stay).get("checkIn") : null;
        String v = Objects.toString(stamped != null ? stamped : checkIn, null);
        return v == null || v.length() < 10 ? null : v.substring(0, 10);
    }
}
