package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.models.booking.SupplierBookingStatus;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks the supplier about bookings whose book call gave no final answer. BOOKING rows are polled every run until
 * {@code app.flight-booking.poll.max-minutes} after they went BOOKING, then become UNKNOWN (hold kept) with an alert;
 * UNKNOWN rows, and rows an admin resolved FAILED in the last 72 hours (a late ticket is still debited), are polled
 * every 10 minutes. Each row is its own unit: the supplier call runs with no transaction open, its answer is applied by
 * {@link PlatformBookingService#applySupplierResult} (keyed wallet calls, so a re-apply posts nothing twice), and the
 * poll is recorded in a separate REQUIRES_NEW transaction.
 * <p>
 * A Tripjack cancel the supplier accepted stays CANCELLING (amendments are asynchronous): those rows are asked every 10
 * minutes and settled by {@link PlatformBookingService#settleConfirmedCancel} once bookingDetails shows them cancelled.
 * Hotels give up after {@code app.hotel-booking.poll.max-minutes}.
 * <p>
 * The same run sweeps rows that can never go on: an expired REVIEWING row (releasing an orphan hold if it has one), and a
 * HELD row whose book was never sent (crash between hold and BOOKING), are ABORTED.
 */
@Slf4j
@Service
public class PlatformBookingPoller {

    static final Duration UNKNOWN_EVERY = Duration.ofMinutes(10);
    static final Duration LATE_TICKET_WINDOW = Duration.ofHours(72);
    static final Duration HELD_STALE = Duration.ofMinutes(10);
    static final Duration REVIEW_STALE = Duration.ofHours(2);
    /** An accepted Tripjack cancel not confirmed by then alerts Voyra (once). */
    static final Duration CANCEL_CONFIRM_WAIT = Duration.ofHours(24);

    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingEventRepository eventRepository;
    private final BookingProviderRegistry providers;
    private final PlatformBookingService bookingService;
    private final PlatformBookingEventRecorder events;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public PlatformBookingPoller(PlatformBookingRepository bookingRepository, PlatformBookingEventRepository eventRepository,
                                 BookingProviderRegistry providers, PlatformBookingService bookingService, PlatformBookingEventRecorder events,
                                 NotificationService notificationService, PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.providers = providers;
        this.bookingService = bookingService;
        this.events = events;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void poll() {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "poll must not run inside a transaction - the supplier call would hold a connection");
        Instant now = Instant.now();
        Instant slowDue = now.minus(UNKNOWN_EVERY);
        List<PlatformBooking> due = new ArrayList<>(bookingRepository.findByStatusIn(
                EnumSet.of(PlatformBookingStatus.BOOKING, PlatformBookingStatus.UNKNOWN)));
        due.addAll(bookingRepository.findByStatusAndResolvedFailedAtAfter(PlatformBookingStatus.FAILED, now.minus(LATE_TICKET_WINDOW)));
        for (PlatformBooking b : due) {
            if (b.getStatus() != PlatformBookingStatus.BOOKING && b.getLastPolledAt() != null && b.getLastPolledAt().isAfter(slowDue)) {
                continue;
            }
            try {
                pollOne(b);
            } catch (Exception e) {
                log.error("Polling platform booking {} failed: {}", b.getId(), e.getMessage(), e);
            }
        }
        for (PlatformBooking b : bookingRepository.findByStatusIn(EnumSet.of(PlatformBookingStatus.CANCELLING))) {
            Optional<Instant> acceptedAt = b.getEnvironment() == SupplierEnvironment.MOCK ? Optional.empty() : acceptedSinceRequested(b.getId());
            if (acceptedAt.isEmpty()) {
                continue; // a mock row settles at once; an unanswered cancel is an admin's (resolveCancel)
            }
            try {
                if (b.getLastPolledAt() == null || !b.getLastPolledAt().isAfter(slowDue)) {
                    pollCancel(b);
                }
                if (acceptedAt.get().isBefore(now.minus(CANCEL_CONFIRM_WAIT))) {
                    tx.executeWithoutResult(s -> alertOverdueCancel(b.getId(), acceptedAt.get()));
                }
            } catch (Exception e) {
                log.error("Polling the cancellation of platform booking {} failed: {}", b.getId(), e.getMessage(), e);
            }
        }
        sweep(now);
    }

    /** When the supplier accepted the latest cancel request; empty if it has not (an earlier, reverted attempt does not count). */
    private Optional<Instant> acceptedSinceRequested(String bookingId) {
        Optional<Instant> accepted = latest(bookingId, "CANCEL_ACCEPTED");
        Optional<Instant> requested = latest(bookingId, "CANCEL_REQUESTED");
        return accepted.filter(a -> requested.isEmpty() || !a.isBefore(requested.get()));
    }

    /** Still CANCELLING a day after the supplier accepted: alert once per acceptance, marked by a CANCEL_OVERDUE event. */
    private void alertOverdueCancel(String bookingId, Instant acceptedAt) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (b == null || b.getStatus() != PlatformBookingStatus.CANCELLING
                || latest(bookingId, "CANCEL_OVERDUE").filter(t -> !t.isBefore(acceptedAt)).isPresent()) {
            return;
        }
        events.record(bookingId, "CANCEL_OVERDUE", Map.of("acceptedAt", acceptedAt.toString()));
        notificationService.platformAlert("Cancellation of booking " + bookingId + " not confirmed",
                "The supplier accepted the cancellation of booking " + bookingId + " (agency " + b.getTenantId() + ") at " + acceptedAt
                        + " but still shows it live. Nothing has been refunded; check with the supplier and resolve the cancellation.");
    }

    private Optional<Instant> latest(String bookingId, String type) {
        return eventRepository.findFirstByPlatformBookingIdAndTypeOrderByCreatedAtDesc(bookingId, type).map(PlatformBookingEvent::getCreatedAt);
    }

    /** Asks whether the supplier has cancelled; only a CANCELLED answer settles. The poll is recorded either way. */
    private void pollCancel(PlatformBooking b) {
        BookResult result = null;
        try {
            result = providers.supplierFor(b).bookingDetails(b.getSupplierBookingId() != null ? b.getSupplierBookingId() : b.getSupplierReviewId());
        } catch (RuntimeException e) {
            log.warn("bookingDetails for the cancellation of platform booking {} gave no answer: {}", b.getId(), e.getClass().getSimpleName());
        }
        try {
            if (result != null && result.status() == SupplierBookingStatus.CANCELLED) {
                bookingService.settleConfirmedCancel(b.getId());
            }
        } finally {
            tx.executeWithoutResult(s -> recordPoll(b.getId()));
        }
    }

    private void sweep(Instant now) {
        for (PlatformBooking b : bookingRepository.findExpiredReviews(now, now.minus(REVIEW_STALE))) {
            abortQuietly(b.getId(), PlatformBookingStatus.REVIEWING, PlatformBookingService.REVIEW_EXPIRED_REASON);
        }
        for (PlatformBooking b : bookingRepository.findByStatusIn(EnumSet.of(PlatformBookingStatus.HELD))) {
            List<PlatformBookingEvent> history = eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId());
            Instant heldAt = firstEventAt(history, "HELD").orElse(b.getCreatedAt());
            if (heldAt.isBefore(now.minus(HELD_STALE)) && firstEventAt(history, "BOOKING").isEmpty()) {
                abortQuietly(b.getId(), PlatformBookingStatus.HELD, "Not sent to supplier");
            }
        }
    }

    private void abortQuietly(String bookingId, PlatformBookingStatus expected, String reason) {
        try {
            bookingService.sweepAbort(bookingId, expected, reason);
            log.info("Released the hold of platform booking {}: {}", bookingId, reason);
        } catch (Exception e) {
            log.error("Sweeping platform booking {} failed: {}", bookingId, e.getMessage(), e);
        }
    }

    private void pollOne(PlatformBooking b) {
        // Tripjack books under the review id, so a book that timed out (no booking id yet) is asked by that
        String supplierId = b.getSupplierBookingId() != null ? b.getSupplierBookingId() : b.getSupplierReviewId();
        BookResult result = null;
        try {
            result = providers.supplierFor(b).bookingDetails(supplierId); // each row asks its own product's supplier
        } catch (RuntimeException e) {
            log.warn("bookingDetails for platform booking {} gave no answer: {}", b.getId(), e.getClass().getSimpleName());
        }
        try {
            if (result != null) {
                bookingService.applySupplierResult(b.getId(), result); // PENDING only records the supplier booking id
            }
        } finally {
            // recorded even when the apply fails, so the row still escalates and keeps its cadence
            tx.executeWithoutResult(s -> recordPoll(b.getId()));
        }
    }

    private void recordPoll(String bookingId) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (b == null) {
            return;
        }
        Instant now = Instant.now();
        b.setPollCount(b.getPollCount() + 1);
        b.setLastPolledAt(now);
        int maxMinutes = providers.pollMaxMinutes(b.getProduct());
        if (b.getStatus() == PlatformBookingStatus.BOOKING && now.isAfter(bookingStartedAt(b).plus(Duration.ofMinutes(maxMinutes)))) {
            b.setStatus(PlatformBookingStatus.UNKNOWN);
            events.record(bookingId, "UNKNOWN", Map.of("polls", b.getPollCount()));
            notificationService.platformAlert("Booking " + bookingId + " outcome unknown",
                    "No final answer from the supplier for booking " + bookingId + " (agency " + b.getTenantId() + ") after "
                            + maxMinutes + " minutes and " + b.getPollCount()
                            + " polls. The wallet hold is kept; check with the supplier and resolve it.");
        }
        bookingRepository.save(b);
    }

    private Instant bookingStartedAt(PlatformBooking b) {
        return firstEventAt(eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId()), "BOOKING").orElse(b.getCreatedAt());
    }

    private static Optional<Instant> firstEventAt(List<PlatformBookingEvent> history, String type) {
        return history.stream().filter(e -> type.equals(e.getType())).map(PlatformBookingEvent::getCreatedAt).findFirst();
    }
}
