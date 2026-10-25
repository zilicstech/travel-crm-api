package com.voyra.crm.service.booking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.dto.PaxInput;
import com.voyra.crm.dto.PlatformBookingConfirmRequest;
import com.voyra.crm.dto.PlatformBookingReviewResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
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
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.models.booking.BookRequest;
import com.voyra.crm.models.booking.BookResult;
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
import com.voyra.crm.service.WalletService;
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
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final PlatformBookingRepository bookingRepository;
    private final FlightBookingProvider provider;
    private final FlightBookingProviderConfig providerConfig;
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

    public PlatformBookingService(PlatformBookingRepository bookingRepository, FlightBookingProvider provider,
                                  FlightBookingProviderConfig providerConfig, AgencyPriceQuoter quoter,
                                  AgencyPricingService pricingService, WalletService walletService,
                                  WalletRepository walletRepository, TenantRepository tenantRepository,
                                  AgentRepository agentRepository, PlatformSupplierCredentialRepository credentialRepository,
                                  NotificationService notificationService, PlatformBookingEventRecorder events,
                                  PaxCodec paxCodec, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                  WalletEntryRepository walletEntryRepository, PlatformAuditService platformAuditService) {
        this.bookingRepository = bookingRepository;
        this.provider = provider;
        this.providerConfig = providerConfig;
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
    }

    /**
     * The agency drops a booking the supplier was never asked to book (REVIEWING or HELD). Releases the hold found by
     * its key, not by {@code holdEntryId}: a REVIEWING row can carry an orphan hold whose tx1 rolled back. Aborting twice
     * returns the aborted row.
     */
    public PlatformBooking abort(String bookingId) {
        CustomUserPrincipal principal = requireBooker();
        return tx.execute(s -> {
            PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId)
                    .filter(x -> principal.tenantId().equals(x.getTenantId()))
                    .orElseThrow(() -> new NotFoundException("Booking not found"));
            if (b.getStatus() == PlatformBookingStatus.ABORTED) {
                return b;
            }
            if (b.getStatus() != PlatformBookingStatus.REVIEWING && b.getStatus() != PlatformBookingStatus.HELD) {
                throw new IllegalStateException("This booking was already sent to the supplier and cannot be aborted");
            }
            walletEntryRepository.findByIdempotencyKey("pb-hold:" + bookingId)
                    .filter(h -> !walletEntryRepository.existsByRelatedEntryIdAndType(h.getId(), WalletEntryType.HOLD_RELEASE))
                    .ifPresent(h -> walletService.releaseHold(h.getId(), "pb-release:" + bookingId));
            b.setStatus(PlatformBookingStatus.ABORTED);
            b.setAbortReason("Aborted by the agency");
            events.record(bookingId, "ABORTED", Map.of("by", principal.userId()));
            return bookingRepository.save(b);
        });
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
        CustomUserPrincipal principal = requireBooker();
        String tenantId = principal.tenantId();
        pricingService.requireInForce(tenantId, PricingProduct.FLIGHT);
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
        SupplierEnvironment environment = SupplierEnvironment.MOCK;
        String credentialId = null;
        if (!"mock".equalsIgnoreCase(providerConfig.getProvider())) {
            PlatformSupplierCredential credential = credentialRepository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)
                    .orElseThrow(() -> new IllegalStateException("Tripjack is not configured on the platform"));
            environment = credential.getEnvironment();
            credentialId = credential.getId();
        }

        FareReview fare = provider.review(request.getOfferId(), request.getAdults(), request.getChildren(), request.getInfants());
        PriceQuote quote = quoter.quote(tenantId, PricingProduct.FLIGHT, fare.totalFare(), fare.baseFare());
        PlatformBooking booking = PlatformBooking.builder()
                .id(UniqueIdResolver.resolve(bookingRepository::existsById))
                .tenantId(tenantId)
                .product(PricingProduct.FLIGHT)
                .clientId(request.getClientId())
                .leadId(request.getLeadId())
                .serviceId(request.getServiceId())
                .status(PlatformBookingStatus.REVIEWING)
                .environment(environment)
                .credentialId(credentialId)
                .supplierReviewId(fare.reviewId())
                .gross(fare.totalFare())
                .base(fare.baseFare())
                .paxCount(request.getAdults() + request.getChildren() + request.getInfants())
                .itinerary(fare.itinerary() == null ? null : objectMapper.convertValue(fare.itinerary(), MAP))
                .fareRules(fare.fareRules())
                // shortcut: Tripjack's review expiry field is unknown (slice 0), so a missing expiry gets 15 minutes
                .reviewExpiresAt(fare.expiresAt() != null ? fare.expiresAt() : Instant.now().plus(DEFAULT_REVIEW_TTL))
                .bookedBy(principal.userId())
                .createdAt(Instant.now())
                .build();
        stamp(booking, quote);
        tx.executeWithoutResult(s -> {
            bookingRepository.save(booking);
            events.record(booking.getId(), "REVIEWED", Map.of("displayed", booking.getDisplayed(), "pricingId", quote.pricingId()));
        });

        BigDecimal searched = request.getSearchDisplayedFare();
        boolean changed = searched != null && searched.compareTo(booking.getDisplayed()) != 0;
        return PlatformBookingReviewResponse.builder()
                .bookingId(booking.getId())
                .displayedFare(booking.getDisplayed())
                .previousDisplayedFare(changed ? searched : null)
                .priceChanged(changed)
                .agencyEarns(quote.agencyEarns())
                .fareRules(booking.getFareRules())
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
        Held held = tx.execute(s -> hold(bookingId, principal.tenantId(), request, issuedBy));
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
            result = provider.book(new BookRequest(booking.getSupplierReviewId(), booking.getGross(),
                    request.getPassengers().stream().map(PlatformBookingService::toPax).toList(),
                    request.getContactEmail(), request.getContactPhone()));
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

    private record Held(PlatformBooking booking, RuntimeException refusal, boolean sendBook) {
    }

    private Held hold(String bookingId, String tenantId, PlatformBookingConfirmRequest request, String issuedBy) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        String payloadHash = paxCodec.hash(List.of(request.getPassengers(), request.getContactEmail(), request.getContactPhone()));
        if (request.getIdempotencyKey().equals(b.getIdempotencyKey())) {
            if (request.getAcceptedDisplayedFare().compareTo(b.getDisplayed()) != 0 || !payloadHash.equals(b.getConfirmPayloadHash())) {
                throw new BookingConflictException("IDEMPOTENCY_KEY_REUSED",
                        "This confirmation key was already used with different details", Map.of());
            }
            return new Held(b, null, false);
        }
        if (b.getStatus() != PlatformBookingStatus.REVIEWING) {
            throw new IllegalStateException("This booking was already confirmed");
        }
        if (bookingRepository.findByIdempotencyKey(request.getIdempotencyKey()).isPresent()) {
            throw new IllegalStateException("This confirmation key was already used for another booking");
        }
        if (request.getPassengers().size() != b.getPaxCount()) {
            throw new IllegalArgumentException("Enter " + b.getPaxCount() + " travellers, as reviewed");
        }
        if (request.getAcceptedDisplayedFare().compareTo(b.getDisplayed()) != 0) {
            throw priceChanged(b.getDisplayed());
        }
        PriceQuote quote = quoter.quote(tenantId, PricingProduct.FLIGHT, b.getGross(), b.getBase());
        if (!quote.pricingId().equals(String.valueOf(b.getPricingSnapshot().get("pricingId")))) {
            stamp(b, quote);
            bookingRepository.save(b);
            events.record(bookingId, "REPRICED", Map.of("displayed", b.getDisplayed(), "pricingId", quote.pricingId()));
            // returned, not thrown, so the re-stamp commits
            return new Held(b, priceChanged(b.getDisplayed()), false);
        }
        if (b.getReviewExpiresAt() != null && Instant.now().isAfter(b.getReviewExpiresAt())) {
            throw new BookingConflictException("REVIEW_EXPIRED", "This fare review has expired - review the fare again", Map.of());
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
                if (b.getStatus() != PlatformBookingStatus.TICKETED && b.getStatus() != PlatformBookingStatus.CANCELLED) {
                    ticket(b, result);
                }
            }
            case FAILED -> {
                if (PlatformBookingStatus.IN_FLIGHT.contains(b.getStatus())) {
                    walletService.releaseHold(b.getHoldEntryId(), "pb-release:" + bookingId);
                    b.setStatus(PlatformBookingStatus.FAILED);
                    b.setSupplierMessage(truncate(result.message()));
                    events.record(bookingId, "FAILED", Map.of("message", String.valueOf(result.message())));
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
                    "pb-debit:" + id, REF_TYPE, id, ReceivableReason.LATE_TICKET);
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
                    REF_TYPE, id, "Commission on flight booking " + result.pnr());
            if (positive(b.getTds())) {
                // the credit above can be swallowed by older DUE receivables, so the TDS may itself fall short
                walletService.debitOrReceivable(b.getTenantId(), WalletEntryType.TDS_DEDUCTED, b.getTds(), "pb-tds:" + id,
                        REF_TYPE, id, ReceivableReason.TDS_SHORTFALL);
            }
        }
        b.setStatus(PlatformBookingStatus.TICKETED);
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
    }

    /** Task 8 hook: writes the CRM mirror after the debit has committed. No-op until then. */
    private void scheduleMirror(PlatformBooking booking) {
    }

    private void stamp(PlatformBooking b, PriceQuote q) {
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

    private CustomUserPrincipal requireBooker() {
        CustomUserPrincipal p = SecurityContextUtil.getCurrentUserOrThrow();
        if (p.isAgencyOwner()) {
            return p;
        }
        if (p.isAgent()) {
            List<ServiceType> handled = agentRepository.findById(p.userId()).map(Agent::getManageableServices).orElse(null);
            if (handled != null && handled.contains(ServiceType.FLIGHT)) {
                return p;
            }
        }
        throw new AccessDeniedException("Only the owner or an agent who handles flights can book flights");
    }

    private String resolveIssuer(CustomUserPrincipal p, String requested) {
        if (p.isAgent() || requested == null || requested.isBlank()) {
            return p.isAgent() ? p.userId() : null;
        }
        return agentRepository.findByIdAndTenantId(requested, p.tenantId()).map(Agent::getId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requested));
    }

    private static BookingConflictException priceChanged(BigDecimal displayed) {
        return new BookingConflictException("PRICE_CHANGED", "The fare is now " + displayed + " - confirm the new price",
                Map.of("displayedFare", displayed));
    }

    private static PaxDetail toPax(PaxInput p) {
        return new PaxDetail(p.getType(), p.getTitle(), p.getFirstName(), p.getLastName(), p.getDob(),
                p.getPassportNo(), p.getPassportExpiry(), p.getNationality());
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
