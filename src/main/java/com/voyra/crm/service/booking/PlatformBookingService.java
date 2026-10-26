package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.HotelReviewRequest;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.PlatformBookingConfirmRequest;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.CancelQuote;
import com.voyra.crm.models.booking.CancelResult;
import com.voyra.crm.models.booking.FareReview;
import com.voyra.crm.models.booking.PaxDetail;
import com.voyra.crm.models.booking.SupplierBookingStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.NotificationService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.WalletService;
import com.voyra.crm.util.PaxTypeCalculator;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PlatformBookingService {

    private static final String REF_TYPE = "PLATFORM_BOOKING";
    private static final Duration DEFAULT_REVIEW_TTL = Duration.ofMinutes(15);
    /** The abort reason the poller's sweep gives an expired review. */
    static final String REVIEW_EXPIRED_REASON = "Review expired";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    /** Pricing-snapshot key of a flight's reviewed {adults, children, infants}. */
    private static final String PAX_MIX = "paxMix";
    /** Pricing-snapshot key of the travel date (flight: first departure, hotel: check-in) the age check runs against. */
    private static final String TRAVEL_DATE = "travelDate";
    /** Event marking that Voyra was alerted about a row the configured supplier does not serve (once per row). */
    static final String SUPPLIER_MISMATCH = "SUPPLIER_MISMATCH";
    static final String NOT_SERVED_MESSAGE = "This booking can't be changed right now. Voyra has been notified.";

    private final PlatformBookingRepository bookingRepository;
    private final BookingProviderRegistry providers;
    private final AgencyPriceQuoter quoter;
    private final AgencyPricingService pricingService;
    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PlatformSupplierCredentialRepository credentialRepository;
    private final NotificationService notificationService;
    private final PlatformBookingEventRecorder events;
    private final PaxCodec paxCodec;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final WalletEntryRepository walletEntryRepository;
    private final PlatformAuditService platformAuditService;
    private final PlatformSettingService settings;
    private final PlatformBookingMirrorService mirrorService;

    public PlatformBookingService(PlatformBookingRepository bookingRepository, BookingProviderRegistry providers,
                                  AgencyPriceQuoter quoter,
                                  AgencyPricingService pricingService, WalletService walletService,
                                  WalletRepository walletRepository, TenantRepository tenantRepository,
                                  AgentRepository agentRepository, PlatformSupplierCredentialRepository credentialRepository,
                                  NotificationService notificationService, PlatformBookingEventRecorder events,
                                  PaxCodec paxCodec, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                  WalletEntryRepository walletEntryRepository, PlatformAuditService platformAuditService,
                                  PlatformSettingService settings, PlatformBookingMirrorService mirrorService) {
        this.bookingRepository = bookingRepository;
        this.providers = providers;
        this.quoter = quoter;
        this.pricingService = pricingService;
        this.walletService = walletService;
        this.walletRepository = walletRepository;
        this.tenantRepository = tenantRepository;
        this.agentRepository = agentRepository;
        this.credentialRepository = credentialRepository;
        this.notificationService = notificationService;
        this.events = events;
        this.paxCodec = paxCodec;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.walletEntryRepository = walletEntryRepository;
        this.platformAuditService = platformAuditService;
        this.settings = settings;
        this.mirrorService = mirrorService;
    }

    /**
     * The agency drops a booking the supplier was never asked to book (REVIEWING or HELD). Releases the hold found by
     * its key, not by {@code holdEntryId}: a REVIEWING row can carry an orphan hold whose tx1 rolled back. Aborting twice
     * returns the aborted row.
     */
    public PlatformBooking abort(String bookingId) {
        CustomUserPrincipal principal = requireBooker();
        return tx.execute(s -> {
            PlatformBooking b = actedOn(principal, bookingRepository.findByIdForUpdate(bookingId));
            if (b.getStatus() == PlatformBookingStatus.ABORTED) {
                return b;
            }
            if (b.getStatus() != PlatformBookingStatus.REVIEWING && b.getStatus() != PlatformBookingStatus.HELD) {
                throw new IllegalStateException("This booking was already sent to the supplier and cannot be aborted");
            }
            return abortLocked(b, "Aborted by the agency", principal.userId());
        });
    }

    /** Poller sweep: aborts the row only if it is still {@code expected} under the lock (a confirm may have won). */
    void sweepAbort(String bookingId, PlatformBookingStatus expected, String reason) {
        tx.executeWithoutResult(s -> bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getStatus() == expected)
                .ifPresent(b -> abortLocked(b, reason, "system")));
    }

    private PlatformBooking abortLocked(PlatformBooking b, String reason, String by) {
        String id = b.getId();
        walletEntryRepository.findByIdempotencyKey("pb-hold:" + id)
                .filter(h -> !walletEntryRepository.existsByRelatedEntryIdAndType(h.getId(), WalletEntryType.HOLD_RELEASE))
                .ifPresent(h -> walletService.releaseHold(h.getId(), "pb-release:" + id));
        b.setStatus(PlatformBookingStatus.ABORTED);
        b.setAbortReason(reason);
        events.record(id, "ABORTED", Map.of("by", by, "reason", reason));
        return bookingRepository.save(b);
    }

    /**
     * Platform admin settles a BOOKING/UNKNOWN row the poller could not: TICKETED debits like a supplier ticket,
     * FAILED releases the hold. Applied through {@link #applyLocked} under the same lock as the poller.
     */
    public PlatformBooking resolve(String bookingId, PlatformBookingStatus outcome, String pnr, List<String> ticketNumbers, String note) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isSuperAdmin()) {
            throw new AccessDeniedException("Only a platform admin can resolve a booking");
        }
        if (outcome != PlatformBookingStatus.TICKETED && outcome != PlatformBookingStatus.FAILED) {
            throw new IllegalArgumentException("A booking can only be resolved as TICKETED or FAILED");
        }
        if (outcome == PlatformBookingStatus.TICKETED && (pnr == null || pnr.isBlank())) {
            throw new IllegalArgumentException("The PNR is required to resolve a booking as ticketed");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A note is required to resolve a booking");
        }
        PlatformBooking booking = tx.execute(s -> {
            PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
            if (b.getStatus() != PlatformBookingStatus.BOOKING && b.getStatus() != PlatformBookingStatus.UNKNOWN) {
                throw new IllegalStateException("Only a booking whose supplier outcome is unknown can be resolved (it is " + b.getStatus() + ")");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("outcome", outcome.name());
            detail.put("from", b.getStatus().name());
            detail.put("by", principal.userId());
            detail.put("note", note);
            events.record(bookingId, "RESOLVED", detail);
            platformAuditService.record(PlatformAuditAction.BOOKING_RESOLVE, REF_TYPE, bookingId, detail);
            SupplierBookingStatus status = outcome == PlatformBookingStatus.TICKETED ? SupplierBookingStatus.TICKETED : SupplierBookingStatus.FAILED;
            if (status == SupplierBookingStatus.FAILED) {
                b.setResolvedFailedAt(Instant.now()); // the poller re-checks for a late ticket for 72 hours
            }
            return applyLocked(bookingId, new BookResult(null, status, pnr, ticketNumbers == null ? List.of() : ticketNumbers,
                    "Resolved by Voyra", null));
        });
        if (booking.getStatus() == PlatformBookingStatus.TICKETED) {
            scheduleMirror(booking);
        }
        return booking;
    }

    /** Re-prices the offer with the supplier and stamps a REVIEWING row; every refusal happens before the supplier call. */
    public PlatformBookingReviewResponse review(FlightReviewRequest request) {
        CustomUserPrincipal principal = requireBooker(PricingProduct.FLIGHT);
        ReviewEnv env = admit(principal, PricingProduct.FLIGHT, request.getClientId(), request.getIssuedByAgentId(),
                request.getServiceId(), request.getLeadId());
        FareReview fare = providers.flights().review(request);
        Map<String, Object> itinerary = itineraryMap(fare.itinerary());
        java.time.LocalDate travelDate = firstDeparture(itinerary);
        return stampReview(principal, PricingProduct.FLIGHT, env, fare, request.getClientId(),
                request.getLeadId(), request.getServiceId(), request.getAdults() + request.getChildren() + request.getInfants(),
                itinerary, request.getSearchDisplayedFare(),
                Map.of("adults", request.getAdults(), "children", request.getChildren(), "infants", request.getInfants()),
                travelDate != null ? travelDate : dateOrNull(request.getDepartDate()));
    }

    /**
     * Hotel twin of {@link #review(FlightReviewRequest)}: same refusals, re-prices the room option for the stay and stamps a
     * REVIEWING HOTEL row whose itinerary is {@code {hotel, stay, rooms, cancellationPolicy}} for the screens and the mirror.
     */
    public PlatformBookingReviewResponse reviewHotel(HotelReviewRequest request) {
        CustomUserPrincipal principal = requireBooker(PricingProduct.HOTEL);
        if (!request.getCheckOut().isAfter(request.getCheckIn())) {
            throw new IllegalArgumentException("Check-out must be after check-in");
        }
        if (request.getCheckIn().isBefore(com.voyra.crm.util.FinancialYear.today())) { // India's today, not the server's
            throw new IllegalArgumentException("Check-in can't be in the past");
        }
        ReviewEnv env = admit(principal, PricingProduct.HOTEL, request.getClientId(), request.getIssuedByAgentId(),
                request.getServiceId(), request.getLeadId());
        FareReview fare = providers.hotels().review(request.getOptionId(), request.getHotelId(), request.getCheckIn(),
                request.getCheckOut(), request.getRooms());
        int guests = request.getRooms().stream()
                .mapToInt(r -> r.getAdults() + (r.getChildAges() == null ? 0 : r.getChildAges().size())).sum();
        return stampReview(principal, PricingProduct.HOTEL, env, fare, request.getClientId(), request.getLeadId(),
                request.getServiceId(), guests, hotelItinerary(request, fare), request.getSearchDisplayedFare(), null, request.getCheckIn());
    }

    private record ReviewEnv(SupplierEnvironment environment, String credentialId) {
    }

    /** Every refusal a review makes before the supplier is asked; returns the environment the booking runs in. */
    private ReviewEnv admit(CustomUserPrincipal principal, PricingProduct product, String clientId, String issuedByAgentId,
                           String serviceId, String leadId) {
        String tenantId = principal.tenantId();
        pricingService.requireInForce(tenantId, product);
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Agency not found"));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("This agency is suspended or deactivated - bookings are not allowed");
        }
        if (Boolean.TRUE.equals(tenant.getBookingBlocked())) {
            throw new IllegalStateException("Bookings are blocked for this agency - contact Voyra");
        }
        if (walletService.hasDueReceivable(tenantId)) {
            throw new ReceivableDueException();
        }
        mirrorService.requireLinks(clientId, leadId, serviceId);
        requireIssuingAgent(principal, clientId, resolveIssuer(principal, issuedByAgentId), serviceId, leadId);
        if (providers.isMock(product)) {
            return new ReviewEnv(SupplierEnvironment.MOCK, null);
        }
        PlatformSupplierCredential credential = credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)
                .orElseThrow(() -> new IllegalStateException("Tripjack is not configured on the platform"));
        requireLiveAllowed(credential.getEnvironment());
        return new ReviewEnv(credential.getEnvironment(), credential.getId());
    }

    private PlatformBookingReviewResponse stampReview(CustomUserPrincipal principal, PricingProduct product, ReviewEnv env,
                                                      FareReview fare, String clientId, String leadId, String serviceId,
                                                      int paxCount, Map<String, Object> itinerary, BigDecimal searched,
                                                      Map<String, Integer> paxMix, java.time.LocalDate travelDate) {
        String tenantId = principal.tenantId();
        if (fare.baseFare() == null) {
            // discount and commission are worked off the base fare, so there is no price to show without it
            throw new BookingConflictException("BASE_FARE_UNAVAILABLE",
                    "The supplier did not give this fare's base fare - pick another fare", Map.of());
        }
        PriceQuote quote = quoter.quote(tenantId, product, fare.totalFare(), fare.baseFare());
        PlatformBooking booking = PlatformBooking.builder()
                .id(UniqueIdResolver.resolve(bookingRepository::existsById))
                .tenantId(tenantId)
                .product(product)
                .clientId(clientId)
                .leadId(leadId)
                .serviceId(serviceId)
                .status(PlatformBookingStatus.REVIEWING)
                .environment(env.environment())
                .credentialId(env.credentialId())
                .supplierReviewId(fare.reviewId())
                .gross(fare.totalFare())
                .base(fare.baseFare())
                .paxCount(paxCount)
                .itinerary(itinerary)
                .fareRules(fare.fareRules())
                // shortcut: Tripjack's review expiry field is unknown (slice 0), so a missing expiry gets 15 minutes
                .reviewExpiresAt(fare.expiresAt() != null ? fare.expiresAt() : Instant.now().plus(DEFAULT_REVIEW_TTL))
                .bookedBy(principal.userId())
                .createdAt(Instant.now())
                .build();
        stamp(booking, quote);
        if (paxMix != null) {
            booking.getPricingSnapshot().put(PAX_MIX, paxMix); // what the supplier priced; confirm must match it
        }
        if (travelDate != null) {
            booking.getPricingSnapshot().put(TRAVEL_DATE, travelDate.toString());
        }
        tx.executeWithoutResult(s -> {
            bookingRepository.save(booking);
            events.record(booking.getId(), "REVIEWED", Map.of("displayed", booking.getDisplayed(), "pricingId", quote.pricingId()));
        });

        boolean changed = searched != null && searched.compareTo(booking.getDisplayed()) != 0;
        return PlatformBookingReviewResponse.builder()
                .bookingId(booking.getId())
                .displayedFare(booking.getDisplayed())
                .previousDisplayedFare(changed ? searched : null)
                .priceChanged(changed)
                .agencyEarns(quote.agencyEarns())
                .fareRules(booking.getFareRules())
                .cancellationPolicy(fare.cancellationPolicy())
                .freeCancellationUntil(fare.freeCancellationUntil())
                .reviewExpiresAt(booking.getReviewExpiresAt())
                .walletAvailable(walletRepository.findByTenantId(tenantId)
                        .map(w -> w.getBalance().subtract(w.getHeld())).orElse(BigDecimal.ZERO))
                .build();
    }

    /**
     * Holds the wallet and books once. Three steps so the supplier call holds no connection or row lock: tx1 locks,
     * validates and holds (HELD), tx2 marks BOOKING, then {@code book} runs with no transaction and its answer is
     * applied by {@link #applySupplierResult}. Returns the booking for the caller to map.
     */
    public PlatformBooking confirm(String bookingId, PlatformBookingConfirmRequest request) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "confirm must not run inside a transaction - the supplier book call would hold a connection and the row lock");
        CustomUserPrincipal principal = requireBooker();
        String issuedBy = resolveIssuer(principal, request.getIssuedByAgentId());
        Held held = tx.execute(s -> hold(bookingId, principal, request, issuedBy));
        if (held.refusal() != null) {
            throw held.refusal();
        }
        if (!held.sendBook()) {
            return held.booking();
        }
        PlatformBooking booking = tx.execute(s -> markBooking(bookingId, request));
        if (booking.getStatus() != PlatformBookingStatus.BOOKING) {
            return booking;
        }
        BookResult result;
        try {
            result = providers.supplierFor(booking).book(bookRequest(booking, request));
        } catch (RuntimeException e) {
            // outcome unknown (timeout, 5xx, empty body): never release the hold or retry book - the poller asks bookingDetails
            log.warn("Book call for platform booking {} gave no clean answer: {}", bookingId, e.getClass().getSimpleName());
            tx.executeWithoutResult(s -> events.record(bookingId, "BOOK_NO_ANSWER", Map.of("error", e.getClass().getSimpleName())));
            return bookingRepository.findById(bookingId).orElse(booking);
        }
        return applySupplierResult(bookingId, result);
    }

    /**
     * Applies a supplier answer once, under the row lock (also used by the poller). TICKETED debits the displayed fare
     * from the hold and credits commission net of TDS; FAILED releases the hold; PENDING leaves the row BOOKING.
     * Every wallet call is keyed, so re-applying after a crash posts nothing twice.
     */
    PlatformBooking applySupplierResult(String bookingId, BookResult result) {
        PlatformBooking booking = tx.execute(s -> applyLocked(bookingId, result));
        if (booking.getStatus() == PlatformBookingStatus.TICKETED) {
            scheduleMirror(booking);
        }
        return booking;
    }

    /** What cancelling this ticketed booking would refund and recall, from the supplier's quote. Posts nothing. */
    public CancelPreview cancelQuote(String bookingId) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "cancelQuote must not run inside a transaction - the supplier call would hold a connection");
        CustomUserPrincipal principal = requireBooker();
        PlatformBooking b = actedOn(principal, bookingRepository.findByIdAndTenantId(bookingId, principal.tenantId()));
        if (b.getStatus() == PlatformBookingStatus.CANCELLING) {
            throw cancelInProgress();
        }
        requireCancellable(b);
        requireServed(b);
        BigDecimal fee = settings.voyraCancelFee();
        BigDecimal refund = refundOf(providers.supplierFor(b).cancelQuote(supplierId(b)).refund(), b.getDisplayed(), fee);
        BigDecimal recall = recallOf(b);
        Map<String, Object> it = b.getProduct() == PricingProduct.HOTEL && b.getItinerary() != null ? b.getItinerary() : Map.of();
        return new CancelPreview(refund, recall, refund == null ? null : refund.subtract(recall), fee, refund == null,
                it.get("cancellationPolicy") == null ? null : it.get("cancellationPolicy").toString(), instantOrNull(it.get("freeCancellationUntil")));
    }

    /**
     * Cancels a ticketed booking whole (spec 7.5). The supplier calls hold no connection or row lock:
     * <ol>
     *   <li>tx1 locks the row, requires TICKETED and moves it to CANCELLING, so a second request never reaches the supplier
     *       (same key: the current state; another key: 409 CANCEL_IN_PROGRESS);</li>
     *   <li>the supplier quotes (an unknown refund goes back to TICKETED, 409) and cancels with no transaction open. A refusal
     *       goes back to TICKETED; no answer stays CANCELLING for {@link #resolveCancel}, since the supplier may have cancelled;</li>
     *   <li>the acceptance is recorded in its own transaction, then tx2 posts REFUND_CREDIT and COMMISSION_RECALL under the
     *       per-booking keys. If tx2 fails the row stays CANCELLING with the acceptance on record, for an admin to settle.</li>
     * </ol>
     * Tripjack only accepts the amendment synchronously, so for it step 3 stops after recording the acceptance: the row
     * stays CANCELLING and the poller posts the money once bookingDetails shows it cancelled ({@link #settleConfirmedCancel}).
     */
    public PlatformBooking cancel(String bookingId, String idempotencyKey) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "cancel must not run inside a transaction - the supplier cancel call would hold a connection and the row lock");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required to cancel a booking");
        }
        CustomUserPrincipal principal = requireBooker();
        // checked before the claim so a refused row stays TICKETED; a row's environment and the provider never change while running
        PlatformBooking current = actedOn(principal, bookingRepository.findByIdAndTenantId(bookingId, principal.tenantId()));
        if (current.getStatus() == PlatformBookingStatus.TICKETED) {
            requireServed(current);
        }
        Claim claim = tx.execute(s -> claimCancel(bookingId, principal, idempotencyKey));
        if (!claim.submit()) {
            return claim.booking();
        }
        String supplierId = supplierId(claim.booking());
        BookingSupplier provider = providers.supplierFor(claim.booking());
        BigDecimal fee = settings.voyraCancelFee();
        CancelQuote quote;
        try {
            quote = provider.cancelQuote(supplierId);
        } catch (RuntimeException e) {
            // nothing was submitted, so the booking is still live at the supplier
            backToTicketed(bookingId, "CANCEL_NO_ANSWER", Map.of("stage", "quote", "error", e.getClass().getSimpleName()));
            throw asSupplierException(e);
        }
        if (!known(quote.refund())) {
            String message = "Voyra will process this cancellation - please contact support";
            backToTicketed(bookingId, "CANCEL_REFUSED", Map.of("code", "REFUND_UNKNOWN", "message", message));
            throw new BookingConflictException("REFUND_UNKNOWN", message, Map.of());
        }
        CancelResult result;
        try {
            result = provider.cancel(supplierId);
        } catch (RuntimeException e) {
            // outcome unknown: stays CANCELLING so a retry never submits again; an admin settles it with resolveCancel
            tx.executeWithoutResult(s -> events.record(bookingId, "CANCEL_NO_ANSWER", Map.of("stage", "cancel", "error", e.getClass().getSimpleName())));
            notificationService.platformAlert("Cancellation of booking " + bookingId + " has no supplier answer",
                    "The supplier cancel call for booking " + bookingId + " failed (" + e.getClass().getSimpleName()
                            + "). The booking stays CANCELLING; check with the supplier and resolve the cancellation.");
            throw asSupplierException(e);
        }
        if (!result.accepted()) {
            // the supplier's own words go on the event for Voyra; the agency gets the fixed sentence
            String message = result.message() != null ? result.message() : AgencySupplierMessage.CANCEL_REFUSED;
            backToTicketed(bookingId, "CANCEL_REFUSED", Map.of("code", "CANCEL_REFUSED", "message", message));
            throw new BookingConflictException("CANCEL_REFUSED", AgencySupplierMessage.CANCEL_REFUSED, Map.of());
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        // a plain string: a JSON number would come back as a double
        accepted.put("refund", result.refund() == null ? "UNKNOWN" : result.refund().toPlainString());
        accepted.put("message", String.valueOf(result.message()));
        tx.executeWithoutResult(s -> events.record(bookingId, "CANCEL_ACCEPTED", accepted));
        if (claim.booking().getEnvironment() != SupplierEnvironment.MOCK) { // booked with Tripjack, whatever the provider is now
            // Tripjack only accepts the amendment here; the booking stays CANCELLING and nothing is posted until
            // the poller sees bookingDetails say cancelled (settleConfirmedCancel). The mock settles at once.
            return claim.booking();
        }
        PlatformBooking cancelled;
        try {
            cancelled = tx.execute(s -> cancelLocked(bookingId, result.refund(), result.message(), fee));
        } catch (RuntimeException e) {
            notificationService.platformAlert("Cancelled booking " + bookingId + " was not settled",
                    "The supplier accepted the cancellation of booking " + bookingId + " with refund " + result.refund()
                            + ", but posting the refund and recall failed (" + e.getClass().getSimpleName()
                            + "). The booking stays CANCELLING; resolve the cancellation with that refund.");
            throw e;
        }
        if (cancelled.getStatus() == PlatformBookingStatus.CANCELLED) {
            scheduleMirrorCancel(cancelled);
        }
        return cancelled;
    }

    /**
     * Platform admin settles a CANCELLING booking whose supplier answer was lost: {@code cancelled} posts the refund
     * ({@code refund} is the supplier's refund R; the Voyra fee and the cap apply) and the recall under the same keys as
     * {@link #cancel}; otherwise the booking goes back to TICKETED.
     */
    public PlatformBooking resolveCancel(String bookingId, boolean cancelled, BigDecimal refund, String note) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isSuperAdmin()) {
            throw new AccessDeniedException("Only a platform admin can resolve a cancellation");
        }
        if (cancelled && (refund == null || refund.signum() < 0)) {
            throw new IllegalArgumentException("The supplier refund (0 or more) is required to resolve a booking as cancelled");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A note is required to resolve a cancellation");
        }
        BigDecimal fee = settings.voyraCancelFee();
        PlatformBooking booking = tx.execute(s -> {
            PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
            if (b.getStatus() != PlatformBookingStatus.CANCELLING) {
                throw new IllegalStateException("Only a booking whose cancellation is in progress can be resolved (it is " + b.getStatus() + ")");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("cancelled", cancelled);
            detail.put("refund", refund == null ? "NONE" : refund);
            detail.put("by", principal.userId());
            detail.put("note", note);
            events.record(bookingId, "RESOLVED", detail);
            platformAuditService.record(PlatformAuditAction.BOOKING_CANCEL_RESOLVE, REF_TYPE, bookingId, detail);
            if (cancelled) {
                return cancelLocked(bookingId, refund, "Resolved by Voyra", fee);
            }
            b.setStatus(PlatformBookingStatus.TICKETED);
            return bookingRepository.save(b);
        });
        if (booking.getStatus() == PlatformBookingStatus.CANCELLED) {
            scheduleMirrorCancel(booking);
        }
        return booking;
    }

    /**
     * The poller saw the supplier show a CANCELLING booking cancelled: posts the refund and recall under the same keys as
     * {@link #cancel}, with the refund the supplier gave when it accepted the cancellation (unknown: nothing is posted and
     * Voyra is alerted, as for any cancel without a figure). A row no longer CANCELLING is left alone.
     */
    void settleConfirmedCancel(String bookingId) {
        // shortcut: settles on the refund given at acceptance; Tripjack's final amendment figure (field unknown until
        // the slice 0 fixtures) should replace it once bookingDetails or the amendment status is mapped
        BigDecimal fee = settings.voyraCancelFee();
        PlatformBooking cancelled = tx.execute(s -> {
            Map<String, Object> accepted = events.lastPayload(bookingId, "CANCEL_ACCEPTED").orElse(Map.of());
            Object message = accepted.get("message");
            return cancelLocked(bookingId, refundOnRecord(accepted.get("refund")), message == null ? null : message.toString(), fee);
        });
        if (cancelled.getStatus() == PlatformBookingStatus.CANCELLED) {
            scheduleMirrorCancel(cancelled);
        }
    }

    /** The refund stored on the CANCEL_ACCEPTED event: a number, or null for "UNKNOWN" / missing / unreadable. */
    private static BigDecimal refundOnRecord(Object v) {
        try {
            return v == null ? null : new BigDecimal(v.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Claim(PlatformBooking booking, boolean submit) {
    }

    private Claim claimCancel(String bookingId, CustomUserPrincipal principal, String idempotencyKey) {
        PlatformBooking b = actedOn(principal, bookingRepository.findByIdForUpdate(bookingId));
        if (b.getStatus() == PlatformBookingStatus.CANCELLED) {
            return new Claim(b, false);
        }
        if (b.getStatus() == PlatformBookingStatus.CANCELLING) {
            boolean sameKey = events.lastPayload(bookingId, "CANCEL_REQUESTED")
                    .map(p -> idempotencyKey.equals(p.get("key"))).orElse(false);
            if (sameKey) {
                return new Claim(b, false);
            }
            throw cancelInProgress();
        }
        requireCancellable(b);
        b.setStatus(PlatformBookingStatus.CANCELLING);
        events.record(bookingId, "CANCEL_REQUESTED", Map.of("by", principal.userId(), "key", idempotencyKey));
        return new Claim(bookingRepository.save(b), true);
    }

    /** The supplier was not asked to cancel, or refused: the booking is live again for a later attempt. */
    private void backToTicketed(String bookingId, String eventType, Map<String, Object> detail) {
        tx.executeWithoutResult(s -> bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getStatus() == PlatformBookingStatus.CANCELLING)
                .ifPresent(b -> {
                    b.setStatus(PlatformBookingStatus.TICKETED);
                    bookingRepository.save(b);
                    events.record(bookingId, eventType, detail);
                }));
    }

    private PlatformBooking cancelLocked(String bookingId, BigDecimal supplierRefund, String message, BigDecimal fee) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
        if (b.getStatus() != PlatformBookingStatus.CANCELLING) {
            return b; // already settled
        }
        String id = b.getId();
        BigDecimal netBefore = b.getVoyraNet();
        // the earning in force: the statement figure once imported, else the estimate stamped at review
        BigDecimal earned = nz(b.getVoyraEarnedActual() != null ? b.getVoyraEarnedActual() : b.getVoyraEarned());
        b.setVoyraEarnedReversal(earned); // the supplier claws back Voyra's commission on the fare
        Map<String, Object> detail = new LinkedHashMap<>();
        boolean offline = false;
        if (!known(supplierRefund)) {
            // accepted without a usable figure: nothing is posted until Voyra confirms the refund with the supplier
            b.setVoyraNet(b.getVoyraNet().subtract(earned));
            detail.put("refund", "UNKNOWN");
            notificationService.platformAlert("Cancelled booking " + id + " needs a manual refund",
                    "The supplier cancelled booking " + id + " for agency " + b.getTenantId() + " without a usable refund figure ("
                            + supplierRefund + ") - post refund manually and recall the commission.");
        } else {
            BigDecimal refund = refundOf(supplierRefund, b.getDisplayed(), fee);
            BigDecimal recall = recallOf(b);
            boolean walletClosed = walletRepository.findByTenantId(b.getTenantId())
                    .map(w -> w.getStatus() == WalletStatus.CLOSED).orElse(false);
            if (walletClosed) {
                // a closed wallet takes no postings: the agency is settled by bank transfer
                offline = true;
                detail.put("offline", true);
                notificationService.platformAlert("Cancelled booking " + id + " - refund offline",
                        "The wallet of agency " + b.getTenantId() + " is closed: refund ₹" + refund + " offline for booking " + id
                                + " (less the commission recall ₹" + recall + "). Nothing was posted.");
            } else {
                if (refund.signum() > 0) {
                    walletService.credit(b.getTenantId(), WalletEntryType.REFUND_CREDIT, refund, "pb-refund:" + id, REF_TYPE, id,
                            "Refund on cancelled " + noun(b) + " " + b.getPnr());
                }
                if (recall.signum() > 0) {
                    walletService.debitOrReceivable(b.getTenantId(), WalletEntryType.COMMISSION_RECALL, recall, "pb-recall:" + id,
                            REF_TYPE, id, ReceivableReason.CANCEL_NET_NEGATIVE);
                }
            }
            b.setCancelRefund(refund);
            // earned reversed, the commission recovered in full (net recall + TDS reversed in the quarterly return),
            // and Voyra keeps whatever of the supplier refund it did not pass on (its fee, or above the cap)
            b.setVoyraNet(b.getVoyraNet().subtract(earned).add(nz(b.getAgencyCommission())).add(supplierRefund.subtract(refund)));
            detail.put("refund", refund);
            detail.put("recall", recall);
        }
        b.setStatus(PlatformBookingStatus.CANCELLED);
        if (b.getMirrorStatus() == MirrorStatus.DONE) {
            b.setMirrorStatus(MirrorStatus.PENDING); // the CRM mirror still has to be cancelled
        }
        b.setCancelledAt(Instant.now());
        b.setSupplierMessage(truncate(message));
        events.record(id, "CANCELLED", detail);
        notificationService.bookingCancelled(b, offline);
        if (b.getVoyraNet().signum() < 0 && b.getVoyraNet().compareTo(netBefore) < 0) {
            notificationService.platformAlert("Loss-making booking " + id,
                    "Voyra net on cancelled booking " + id + " for agency " + b.getTenantId() + " fell to " + b.getVoyraNet());
        }
        return bookingRepository.save(b);
    }

    private static BookingConflictException cancelInProgress() {
        return new BookingConflictException("CANCEL_IN_PROGRESS", "This booking is already being cancelled", Map.of());
    }

    private static SupplierException asSupplierException(RuntimeException e) {
        return e instanceof SupplierException se ? se : new SupplierException("Supplier cancel call failed", e);
    }

    private static void requireCancellable(PlatformBooking b) {
        if (b.getStatus() != PlatformBookingStatus.TICKETED) {
            throw new IllegalStateException("Only a ticketed booking can be cancelled (it is " + b.getStatus() + ")");
        }
    }

    /** Tripjack books under the review id, so a booking that never reported its own id is addressed by that. */
    private static String supplierId(PlatformBooking b) {
        return b.getSupplierBookingId() != null ? b.getSupplierBookingId() : b.getSupplierReviewId();
    }

    /** A missing or negative supplier refund is not a figure Voyra can post. */
    private static boolean known(BigDecimal supplierRefund) {
        return supplierRefund != null && supplierRefund.signum() >= 0;
    }

    /** {@code max(0, R - fee)} capped at what the agency paid; null when the supplier gave no usable figure. */
    private static BigDecimal refundOf(BigDecimal supplierRefund, BigDecimal displayed, BigDecimal fee) {
        return known(supplierRefund) ? supplierRefund.subtract(fee).max(BigDecimal.ZERO).min(displayed) : null;
    }

    /** What the agency was credited for the ticket: commission net of TDS, or nothing when there was no commission. */
    private static BigDecimal recallOf(PlatformBooking b) {
        return positive(b.getAgencyCommission()) ? b.getAgencyCommission().subtract(nz(b.getTds())) : BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private record Held(PlatformBooking booking, RuntimeException refusal, boolean sendBook) {
    }

    private Held hold(String bookingId, CustomUserPrincipal principal, PlatformBookingConfirmRequest request, String issuedBy) {
        String tenantId = principal.tenantId();
        PlatformBooking b = actedOn(principal, bookingRepository.findByIdForUpdate(bookingId));
        String payloadHash = paxCodec.hash(List.of(request.getPassengers(), request.getContactEmail(), request.getContactPhone()));
        if (request.getIdempotencyKey().equals(b.getIdempotencyKey())) {
            if (request.getAcceptedDisplayedFare().compareTo(b.getDisplayed()) != 0 || !payloadHash.equals(b.getConfirmPayloadHash())) {
                throw new BookingConflictException("IDEMPOTENCY_KEY_REUSED",
                        "This confirmation key was already used with different details", Map.of());
            }
            return new Held(b, null, false);
        }
        if (b.getStatus() == PlatformBookingStatus.ABORTED && REVIEW_EXPIRED_REASON.equals(b.getAbortReason())) {
            throw reviewExpired(b.getProduct()); // the poller swept it
        }
        if (b.getStatus() != PlatformBookingStatus.REVIEWING) {
            throw new IllegalStateException("This booking was already confirmed");
        }
        requireLiveAllowed(b.getEnvironment()); // switched off since the review: stays REVIEWING, the sweep expires it
        if (!providers.serves(b)) {
            alertNotServed(b);
            return new Held(b, notServed(), false); // returned, not thrown, so the alert's event commits
        }
        if (bookingRepository.findByIdempotencyKey(request.getIdempotencyKey()).isPresent()) {
            throw new IllegalStateException("This confirmation key was already used for another booking");
        }
        requireIssuingAgent(principal, b.getClientId(), issuedBy, b.getServiceId(), b.getLeadId());
        if (b.getProduct() == PricingProduct.HOTEL) {
            requireGuestsPerRoom(b, request.getPassengers());
        } else if (request.getPassengers().size() != b.getPaxCount()) {
            throw new IllegalArgumentException("Enter " + b.getPaxCount() + " travellers, as reviewed");
        } else {
            requireReviewedMix(b, request.getPassengers());
            requirePaxTypesFit(request.getPassengers(), travelDateOf(b));
        }
        if (request.getAcceptedDisplayedFare().compareTo(b.getDisplayed()) != 0) {
            throw priceChanged(b.getProduct(), b.getDisplayed());
        }
        PriceQuote quote = quoter.quote(tenantId, b.getProduct(), b.getGross(), b.getBase());
        if (!quote.pricingId().equals(String.valueOf(b.getPricingSnapshot().get("pricingId")))) {
            stamp(b, quote);
            bookingRepository.save(b);
            events.record(bookingId, "REPRICED", Map.of("displayed", b.getDisplayed(), "pricingId", quote.pricingId()));
            // returned, not thrown, so the re-stamp commits
            return new Held(b, priceChanged(b.getProduct(), b.getDisplayed(), quote.agencyEarns()), false);
        }
        if (b.getReviewExpiresAt() != null && Instant.now().isAfter(b.getReviewExpiresAt())) {
            throw reviewExpired(b.getProduct());
        }
        walletRepository.findByTenantId(tenantId).ifPresent(w -> requireMatchingWallet(b.getEnvironment(), w));
        // everything that can fail runs before the hold, so a refusal never leaves a committed hold behind
        String paxEnc = paxCodec.encrypt(Map.of("passengers", request.getPassengers(),
                "contactEmail", request.getContactEmail(), "contactPhone", request.getContactPhone()));

        WalletEntry hold = walletService.hold(tenantId, b.getDisplayed(), "pb-hold:" + bookingId, REF_TYPE, bookingId);
        b.setHoldEntryId(hold.getId());
        b.setIdempotencyKey(request.getIdempotencyKey());
        b.setConfirmPayloadHash(payloadHash);
        b.setIssuedByAgentId(issuedBy);
        b.setPaxEnc(paxEnc);
        b.setStatus(PlatformBookingStatus.HELD);
        bookingRepository.save(b);
        events.record(bookingId, "HELD", Map.of("amount", b.getDisplayed(), "holdEntryId", hold.getId()));
        return new Held(b, null, true);
    }

    private PlatformBooking markBooking(String bookingId, PlatformBookingConfirmRequest request) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
        if (b.getStatus() != PlatformBookingStatus.HELD) {
            return b; // aborted or resolved in between: do not book
        }
        b.setStatus(PlatformBookingStatus.BOOKING);
        bookingRepository.save(b);
        events.record(bookingId, "BOOKING", Map.of("paxCount", request.getPassengers().size(),
                "types", request.getPassengers().stream().map(PaxInput::getType).toList()));
        return b;
    }

    private PlatformBooking applyLocked(String bookingId, BookResult result) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
        if (result.supplierBookingId() != null && b.getSupplierBookingId() == null) {
            b.setSupplierBookingId(result.supplierBookingId());
        }
        switch (result.status()) {
            case TICKETED -> {
                // a ticket already applied (or being cancelled) is never debited again
                if (b.getStatus() != PlatformBookingStatus.TICKETED && b.getStatus() != PlatformBookingStatus.CANCELLING
                        && b.getStatus() != PlatformBookingStatus.CANCELLED) {
                    ticket(b, result);
                }
            }
            case FAILED, CANCELLED -> { // a booking cancelled before it ever ticketed is a failed booking
                if (PlatformBookingStatus.IN_FLIGHT.contains(b.getStatus())) {
                    walletService.releaseHold(b.getHoldEntryId(), "pb-release:" + bookingId);
                    b.setStatus(PlatformBookingStatus.FAILED);
                    b.setSupplierMessage(truncate(result.message()));
                    events.record(bookingId, "FAILED", Map.of("message", String.valueOf(result.message())));
                    notificationService.bookingFailed(b);
                }
            }
            case PENDING -> { /* stays BOOKING; the poller asks again */ }
        }
        return bookingRepository.save(b);
    }

    private void ticket(PlatformBooking b, BookResult result) {
        String id = b.getId();
        WalletEntry debit;
        if (walletEntryRepository.findByIdempotencyKey("pb-release:" + id).isPresent()) {
            // late ticket: the hold went back to the agency (resolved FAILED) - still debited, receivable if short
            debit = walletService.debitOrReceivable(b.getTenantId(), WalletEntryType.BOOKING_DEBIT, b.getDisplayed(),
                    "pb-debit:" + id, REF_TYPE, id, ReceivableReason.LATE_TICKET, b.getHoldEntryId());
            events.record(id, "LATE_TICKET", Map.of("previousStatus", b.getStatus().name()));
            notificationService.platformAlert("Late ticket on booking " + id,
                    "Booking " + id + " for agency " + b.getTenantId() + " ticketed after its hold was released ("
                            + b.getStatus() + "); debited " + b.getDisplayed() + ", with a receivable for any shortfall.");
        } else {
            if (b.getHoldEntryId() == null) {
                throw new IllegalStateException("Booking " + id + " has no wallet hold to debit");
            }
            debit = walletService.debitHeld(b.getHoldEntryId(), b.getDisplayed(), "pb-debit:" + id, REF_TYPE, id);
        }
        b.setDebitEntryId(debit.getId());
        if (positive(b.getAgencyCommission())) {
            walletService.credit(b.getTenantId(), WalletEntryType.COMMISSION_CREDIT, b.getAgencyCommission(), "pb-comm:" + id,
                    REF_TYPE, id, "Commission on " + noun(b) + " " + result.pnr());
            if (positive(b.getTds())) {
                // the credit above can be swallowed by older DUE receivables, so the TDS may itself fall short
                walletService.debitOrReceivable(b.getTenantId(), WalletEntryType.TDS_DEDUCTED, b.getTds(), "pb-tds:" + id,
                        REF_TYPE, id, ReceivableReason.TDS_SHORTFALL);
            }
        }
        b.setStatus(PlatformBookingStatus.TICKETED);
        if (b.getClientId() != null) {
            b.setMirrorStatus(MirrorStatus.PENDING); // committed with the debit, so the repair job finds it even after a crash
        }
        b.setPnr(result.pnr());
        b.setTicketNumbers(result.ticketNumbers());
        b.setTicketedAt(Instant.now());
        b.setSupplierMessage(truncate(result.message()));
        // a null or non-positive amount means the supplier did not say what it charged - not a variance
        if (positive(result.amount()) && result.amount().compareTo(b.getGross()) != 0) {
            BigDecimal variance = result.amount().subtract(b.getGross());
            b.setVoyraNet(b.getVoyraNet().subtract(variance));
            events.record(id, "VARIANCE", Map.of("reviewed", b.getGross(), "charged", result.amount()));
            notificationService.platformAlert("Supplier fare variance on booking " + id,
                    "Reviewed " + b.getGross() + ", supplier charged " + result.amount() + "; the agency was debited the reviewed fare.");
        }
        if (b.getVoyraNet().signum() < 0) {
            notificationService.platformAlert("Loss-making booking " + id,
                    "Voyra net on booking " + id + " for agency " + b.getTenantId() + " is " + b.getVoyraNet());
        }
        events.record(id, "TICKETED", Map.of("pnr", String.valueOf(result.pnr()), "debitEntryId", debit.getId()));
        notificationService.bookingTicketed(b);
    }

    /** Writes the CRM mirror after the debit committed, outside any transaction; a failure is the repair job's, never the ticket's. */
    private void scheduleMirror(PlatformBooking booking) {
        mirrorService.sync(booking.getId());
    }

    /** Cancels the CRM mirror after the refund and recall committed, outside any transaction. */
    private void scheduleMirrorCancel(PlatformBooking booking) {
        mirrorService.sync(booking.getId());
    }

    private void stamp(PlatformBooking b, PriceQuote q) {
        Map<String, Object> previous = b.getPricingSnapshot() == null ? Map.of() : b.getPricingSnapshot(); // mix and date survive a re-price
        b.setDiscountPct(q.discountPct());
        b.setDisplayed(q.displayed());
        b.setVoyraEarned(q.voyraEarned());
        b.setVoyraEarnedSource(VoyraEarnedSource.ESTIMATE);
        b.setAgencyCommission(q.agencyCommission());
        b.setTds(q.tds());
        b.setVoyraNet(q.voyraNet());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pricingId", q.pricingId());
        snapshot.put("discountPct", q.discountPct());
        snapshot.put("commissionPct", q.commissionPct());
        snapshot.put("gross", b.getGross());
        snapshot.put("base", b.getBase());
        snapshot.put("displayed", q.displayed());
        snapshot.put("voyraEarned", q.voyraEarned());
        snapshot.put("agencyCommission", q.agencyCommission());
        snapshot.put("tds", q.tds());
        snapshot.put("agencyEarns", q.agencyEarns());
        snapshot.put("voyraNet", q.voyraNet());
        for (String kept : List.of(PAX_MIX, TRAVEL_DATE)) {
            if (previous.get(kept) != null) {
                snapshot.put(kept, previous.get(kept));
            }
        }
        b.setPricingSnapshot(snapshot);
    }

    /** Simulated money (UAT, mock) never buys real tickets and real money never pays for test ones. */
    private static void requireMatchingWallet(SupplierEnvironment environment, Wallet wallet) {
        boolean testWallet = Boolean.TRUE.equals(wallet.getIsTest());
        if (environment == SupplierEnvironment.PROD && testWallet) {
            throw new BookingConflictException("LIVE_WALLET_REQUIRED",
                    "A test wallet cannot pay for a live booking - contact Voyra", Map.of());
        }
        if (environment != SupplierEnvironment.PROD && !testWallet) {
            throw new BookingConflictException("TEST_WALLET_REQUIRED",
                    "Test bookings can only use a test wallet - contact Voyra", Map.of());
        }
    }

    /** Owner or agent; whether the agent handles the booking's product is checked once the row is loaded ({@link #actedOn}). */
    private CustomUserPrincipal requireBooker() {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        if (p.isAgencyOwner() || p.isAgent()) {
            return p;
        }
        throw new AccessDeniedException("Only the owner or an agent can book through Voyra");
    }

    private CustomUserPrincipal requireBooker(PricingProduct product) {
        CustomUserPrincipal p = requireBooker();
        requireHandles(p, product);
        return p;
    }

    /** The owner books everything; an agent only the products in their manageable services (FLIGHT, HOTEL). */
    private void requireHandles(CustomUserPrincipal p, PricingProduct product) {
        if (p.isAgencyOwner()) {
            return;
        }
        ServiceType service = product == PricingProduct.HOTEL ? ServiceType.HOTEL : ServiceType.FLIGHT;
        List<ServiceType> handled = agentRepository.findById(p.userId()).map(Agent::getManageableServices).orElse(null);
        if (handled == null || !handled.contains(service)) {
            String what = product == PricingProduct.HOTEL ? "hotels" : "flights";
            throw new AccessDeniedException("Only the owner or an agent who handles " + what + " can book " + what);
        }
    }

    /**
     * The booking this caller may act on: another agency's, or for an agent a colleague's, is absent (404) - checked
     * before the product (403), so an agent never learns a colleague's booking exists.
     */
    private PlatformBooking actedOn(CustomUserPrincipal p, java.util.Optional<PlatformBooking> row) {
        PlatformBooking b = row.filter(x -> actsOn(p, x)).orElseThrow(() -> new NotFoundException("Booking not found"));
        requireHandles(p, b.getProduct());
        return b;
    }

    private String resolveIssuer(CustomUserPrincipal p, String requested) {
        if (p.isAgent() || requested == null || requested.isBlank()) {
            return p.isAgent() ? p.userId() : null;
        }
        return agentRepository.findByIdAndTenantId(requested, p.tenantId()).map(Agent::getId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requested));
    }

    /**
     * A booking for a CRM client gets a CRM mirror, and a CRM booking needs its agent (spec 6): the booking agent, the
     * issuing agent the owner picked, or the lead's agent. Refused before the supplier and before any hold.
     */
    private void requireIssuingAgent(CustomUserPrincipal p, String clientId, String issuedBy, String serviceId, String leadId) {
        if (clientId == null || clientId.isBlank() || p.isAgent() || issuedBy != null
                || mirrorService.hasLeadAgent(p.tenantId(), serviceId, leadId)) {
            return;
        }
        throw new BookingRequestException("ISSUING_AGENT_REQUIRED", "Choose the agent this booking is issued by");
    }

    /** Kill switch: with tripjack_live_ready off no new live booking is reviewed or confirmed; rows already sent keep settling. */
    private void requireLiveAllowed(SupplierEnvironment environment) {
        if (environment == SupplierEnvironment.PROD && !settings.tripjackLiveReady()) {
            throw new BookingConflictException("LIVE_BOOKINGS_PAUSED",
                    "Booking through Voyra is paused for now - please try again later or contact Voyra", Map.of());
        }
    }

    /** Refuses (409) a booking the configured provider did not book, after alerting Voyra once; no supplier call, no money. */
    private void requireServed(PlatformBooking b) {
        if (!providers.serves(b)) {
            tx.executeWithoutResult(s -> bookingRepository.findByIdForUpdate(b.getId()).ifPresent(this::alertNotServed));
            throw notServed();
        }
    }

    private static BookingConflictException notServed() {
        return new BookingConflictException("BOOKING_UNAVAILABLE", NOT_SERVED_MESSAGE, Map.of());
    }

    /**
     * The provider configured now is not the one this row was booked with (mock vs Tripjack): alerts Voyra once per row,
     * marked by a SUPPLIER_MISMATCH event, in the caller's transaction (the row locked). Also used by the poller.
     */
    void alertNotServed(PlatformBooking b) {
        if (events.lastPayload(b.getId(), SUPPLIER_MISMATCH).isPresent()) {
            return;
        }
        String configured = providers.isMock(b.getProduct()) ? "the mock" : "Tripjack";
        events.record(b.getId(), SUPPLIER_MISMATCH, Map.of("environment", b.getEnvironment().name(), "status", b.getStatus().name(),
                "configured", configured));
        notificationService.platformAlert("Booking " + b.getId() + " is not served by the configured supplier",
                "Booking " + b.getId() + " (agency " + b.getTenantId() + ", " + b.getStatus() + ") was booked in the " + b.getEnvironment()
                        + " environment, but the " + b.getProduct() + " booking provider configured now is " + configured
                        + ". Voyra will not ask the supplier about it or move money for it until the matching provider is configured "
                        + "again - restore it, or check with the supplier and resolve the booking.");
    }

    private static String priceWord(PricingProduct product) {
        return product == PricingProduct.HOTEL ? "rate" : "fare";
    }

    private static BookingConflictException reviewExpired(PricingProduct product) {
        String w = priceWord(product);
        return new BookingConflictException("REVIEW_EXPIRED", "This " + w + " review has expired - review the " + w + " again", Map.of());
    }

    private static BookingConflictException priceChanged(PricingProduct product, BigDecimal displayed) {
        return priceChanged(product, displayed, null);
    }

    /** {@code agencyEarns} rides along when the re-price changed it, so the screen shows the new earning with the new price. */
    private static BookingConflictException priceChanged(PricingProduct product, BigDecimal displayed, BigDecimal agencyEarns) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("displayedFare", displayed);
        if (agencyEarns != null) {
            details.put("agencyEarns", agencyEarns);
        }
        return new BookingConflictException("PRICE_CHANGED", "The " + priceWord(product) + " is now " + displayed + " - confirm the new price", details);
    }

    private static PaxDetail toPax(PaxInput p) {
        return new PaxDetail(p.getType(), p.getTitle(), p.getFirstName(), p.getLastName(), p.getDob(),
                p.getPassportNo(), p.getPassportExpiry(), p.getNationality());
    }

    /**
     * Each traveller is booked in the slot their date of birth gives on the travel date ({@link PaxTypeCalculator}): a
     * child who turns 12 before departure flies as an adult, and the airline refuses a mismatch. A child or infant needs
     * a date of birth; an adult without one is taken as given. With no travel date on the row only the DOB rule applies.
     */
    private static void requirePaxTypesFit(List<PaxInput> passengers, java.time.LocalDate travelDate) {
        for (int i = 0; i < passengers.size(); i++) {
            PaxInput p = passengers.get(i);
            String who = "Traveller " + (i + 1);
            if (!"ADULT".equals(p.getType()) && p.getDob() == null) {
                throw new BookingRequestException("PAX_DOB_REQUIRED", who + " needs a date of birth to travel as " + slot(p.getType()));
            }
            PaxType actual = PaxTypeCalculator.paxTypeAt(p.getDob(), travelDate);
            if (actual != PaxType.UNKNOWN && !actual.name().equals(p.getType())) {
                throw new BookingRequestException("PAX_TYPE_MISMATCH", who + " is " + slot(actual.name()) + " on the travel date ("
                        + travelDate + ") but was entered as " + slot(p.getType()) + " - review the fare with the right travellers");
            }
        }
    }

    /** The travellers' declared types add up to the reviewed adults, children and infants (rows reviewed before the mix was stamped skip this). */
    private static void requireReviewedMix(PlatformBooking b, List<PaxInput> passengers) {
        if (b.getPricingSnapshot() == null || !(b.getPricingSnapshot().get(PAX_MIX) instanceof Map<?, ?> mix)) {
            return;
        }
        for (String[] t : new String[][]{{"ADULT", "adults"}, {"CHILD", "children"}, {"INFANT", "infants"}}) {
            long declared = passengers.stream().filter(p -> t[0].equals(p.getType())).count();
            Object reviewed = mix.get(t[1]);
            if (reviewed instanceof Number n && n.longValue() != declared) {
                throw new BookingRequestException("PAX_MIX_CHANGED", "The fare was reviewed for " + mix.get("adults") + " adult(s), "
                        + mix.get("children") + " child(ren) and " + mix.get("infants") + " infant(s) - enter those travellers or review the fare again");
            }
        }
    }

    private static String slot(String type) {
        return switch (type == null ? "" : type) {
            case "CHILD" -> "a child (2-11)";
            case "INFANT" -> "an infant (under 2)";
            default -> "an adult";
        };
    }

    /** The travel date stamped at review; rows reviewed before it was stamped fall back to their itinerary. */
    private java.time.LocalDate travelDateOf(PlatformBooking b) {
        Object stamped = b.getPricingSnapshot() == null ? null : b.getPricingSnapshot().get(TRAVEL_DATE);
        java.time.LocalDate d = stamped == null ? null : dateOrNull(stamped.toString());
        return d != null ? d : firstDeparture(b.getItinerary());
    }

    private static java.time.LocalDate dateOrNull(String s) {
        try {
            return s == null || s.length() < 10 ? null : java.time.LocalDate.parse(s.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** The earliest departure date among the itinerary's segments ({@code sI[].dt}, Tripjack's and the mock's shape); null if none. */
    private java.time.LocalDate firstDeparture(Map<String, Object> itinerary) {
        if (itinerary == null) {
            return null;
        }
        java.time.LocalDate first = null;
        for (JsonNode legs : objectMapper.valueToTree(itinerary).findValues("sI")) {
            for (JsonNode seg : legs) {
                String dt = seg.path("dt").asText("");
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse(dt.length() >= 10 ? dt.substring(0, 10) : dt);
                    first = first == null || d.isBefore(first) ? d : first;
                } catch (java.time.format.DateTimeParseException e) {
                    // an unreadable date is skipped, not guessed
                }
            }
        }
        return first;
    }

    private static int roomOf(PaxInput p) {
        return p.getRoomIndex() == null ? 0 : p.getRoomIndex();
    }

    /** Passengers in request order, split per room (flights: one group); the lead PAN is the first PAN among the room leads. */
    private static BookRequest bookRequest(PlatformBooking b, PlatformBookingConfirmRequest request) {
        java.util.TreeMap<Integer, List<PaxInput>> byRoom = new java.util.TreeMap<>();
        request.getPassengers().forEach(p -> byRoom.computeIfAbsent(roomOf(p), k -> new java.util.ArrayList<>()).add(p));
        String leadPan = byRoom.values().stream().map(g -> g.get(0).getPan())
                .filter(pan -> pan != null && !pan.isBlank()).findFirst().orElse(null);
        return new BookRequest(b.getSupplierReviewId(), b.getGross(),
                request.getPassengers().stream().map(PlatformBookingService::toPax).toList(),
                request.getContactEmail(), request.getContactPhone(),
                byRoom.values().stream().map(g -> g.stream().map(PlatformBookingService::toPax).toList()).toList(), leadPan);
    }

    /**
     * Hotels: every reviewed room has a lead guest with a title (the first guest given for it) and no more guests than its
     * reviewed adults + children; other guests are optional.
     */
    private static void requireGuestsPerRoom(PlatformBooking b, List<PaxInput> guests) {
        Object reviewed = b.getItinerary() == null ? null : b.getItinerary().get("rooms");
        List<?> occupancy = reviewed instanceof List<?> list ? list : List.of();
        int rooms = occupancy.isEmpty() ? 1 : occupancy.size();
        if (guests.size() > b.getPaxCount()) {
            throw new IllegalArgumentException("Enter at most " + b.getPaxCount() + " guests, as reviewed");
        }
        for (int room = 0; room < rooms; room++) {
            int r = room;
            PaxInput lead = guests.stream().filter(g -> roomOf(g) == r).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Enter the lead guest of room " + (r + 1)));
            if (lead.getTitle() == null || lead.getTitle().isBlank()) {
                throw new IllegalArgumentException("Enter the title of the lead guest of room " + (r + 1));
            }
            if (r < occupancy.size() && occupancy.get(r) instanceof Map<?, ?> m && m.get("adults") instanceof Number adults) {
                int cap = adults.intValue() + (m.get("childAges") instanceof List<?> ages ? ages.size() : 0);
                if (guests.stream().filter(g -> roomOf(g) == r).count() > cap) {
                    throw new IllegalArgumentException("Enter at most " + cap + " guests in room " + (r + 1) + ", as reviewed");
                }
            }
        }
        if (guests.stream().anyMatch(g -> roomOf(g) >= rooms)) {
            throw new IllegalArgumentException("A guest is in a room that was not reviewed");
        }
    }

    /** {@code {hotel:{name, city, address}, stay:{checkIn, checkOut, nights}, rooms:[...], cancellationPolicy, freeCancellationUntil}}. */
    private static Map<String, Object> hotelItinerary(HotelReviewRequest r, FareReview fare) {
        Map<String, Object> hotel = new LinkedHashMap<>();
        hotel.put("name", r.getHotelName());
        hotel.put("city", r.getCity());
        hotel.put("address", r.getAddress());
        Map<String, Object> stay = new LinkedHashMap<>();
        stay.put("checkIn", r.getCheckIn().toString());
        stay.put("checkOut", r.getCheckOut().toString());
        stay.put("nights", java.time.temporal.ChronoUnit.DAYS.between(r.getCheckIn(), r.getCheckOut()));
        List<Map<String, Object>> rooms = r.getRooms().stream().map(room -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adults", room.getAdults());
            m.put("childAges", room.getChildAges() == null ? List.of() : room.getChildAges());
            m.put("roomName", r.getRoomName());
            m.put("mealBasis", r.getMealBasis());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hotel", hotel);
        out.put("stay", stay);
        out.put("rooms", rooms);
        out.put("cancellationPolicy", fare.cancellationPolicy());
        out.put("freeCancellationUntil", fare.freeCancellationUntil() == null ? null : fare.freeCancellationUntil().toString());
        return out;
    }

    private static String noun(PlatformBooking b) {
        return b.getProduct() == PricingProduct.HOTEL ? "hotel booking" : "flight booking";
    }

    /** Stores whatever shape the supplier sent: an object as-is, an array (Tripjack tripInfos) under "tripInfos". */
    private Map<String, Object> itineraryMap(JsonNode itinerary) {
        if (itinerary == null || itinerary.isNull()) {
            return null;
        }
        return itinerary.isObject() ? objectMapper.convertValue(itinerary, MAP) : Map.of("tripInfos", objectMapper.convertValue(itinerary, Object.class));
    }

    /** A booking this caller may act on: same agency, and an agent only their own (a colleague's is simply absent). */
    private static boolean actsOn(CustomUserPrincipal p, PlatformBooking b) {
        return p.tenantId().equals(b.getTenantId()) && (!p.isAgent() || p.userId().equals(b.getBookedBy()));
    }

    /** An ISO instant stored in the itinerary JSON, or null when absent or unreadable. */
    private static Instant instantOrNull(Object v) {
        try {
            return v == null ? null : Instant.parse(v.toString());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
