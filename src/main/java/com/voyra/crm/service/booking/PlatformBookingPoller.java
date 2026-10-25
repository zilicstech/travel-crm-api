package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.models.booking.BookResult;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Asks the supplier about bookings whose book call gave no final answer. BOOKING rows are polled every run until
 * {@code app.flight-booking.poll.max-minutes} after they went BOOKING, then become UNKNOWN (hold kept) with an alert;
 * UNKNOWN rows are polled every 10 minutes until an answer or an admin resolve. Each row is its own unit: the supplier
 * call runs with no transaction open, its answer is applied by {@link PlatformBookingService#applySupplierResult}
 * (keyed wallet calls, so a re-apply posts nothing twice), and the poll is recorded in a separate REQUIRES_NEW
 * transaction.
 */
@Slf4j
@Service
public class PlatformBookingPoller {

    static final Duration UNKNOWN_EVERY = Duration.ofMinutes(10);

    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingEventRepository eventRepository;
    private final FlightBookingProvider provider;
    private final FlightBookingProviderConfig config;
    private final PlatformBookingService bookingService;
    private final PlatformBookingEventRecorder events;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public PlatformBookingPoller(PlatformBookingRepository bookingRepository, PlatformBookingEventRepository eventRepository,
                                 FlightBookingProvider provider, FlightBookingProviderConfig config,
                                 PlatformBookingService bookingService, PlatformBookingEventRecorder events,
                                 NotificationService notificationService, PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.provider = provider;
        this.config = config;
        this.bookingService = bookingService;
        this.events = events;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void poll() {
        Instant unknownDue = Instant.now().minus(UNKNOWN_EVERY);
        List<PlatformBooking> due = bookingRepository.findByStatusIn(
                        EnumSet.of(PlatformBookingStatus.BOOKING, PlatformBookingStatus.UNKNOWN)).stream()
                .filter(b -> b.getStatus() == PlatformBookingStatus.BOOKING
                        || b.getLastPolledAt() == null || !b.getLastPolledAt().isAfter(unknownDue))
                .toList();
        for (PlatformBooking b : due) {
            try {
                pollOne(b);
            } catch (Exception e) {
                log.error("Polling platform booking {} failed: {}", b.getId(), e.getMessage(), e);
            }
        }
    }

    private void pollOne(PlatformBooking b) {
        // Tripjack books under the review id, so a book that timed out (no booking id yet) is asked by that
        String supplierId = b.getSupplierBookingId() != null ? b.getSupplierBookingId() : b.getSupplierReviewId();
        BookResult result = null;
        try {
            result = provider.bookingDetails(supplierId);
        } catch (RuntimeException e) {
            log.warn("bookingDetails for platform booking {} gave no answer: {}", b.getId(), e.getClass().getSimpleName());
        }
        if (result != null) {
            bookingService.applySupplierResult(b.getId(), result); // PENDING only records the supplier booking id
        }
        tx.executeWithoutResult(s -> recordPoll(b.getId()));
    }

    private void recordPoll(String bookingId) {
        PlatformBooking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (b == null) {
            return;
        }
        Instant now = Instant.now();
        b.setPollCount(b.getPollCount() + 1);
        b.setLastPolledAt(now);
        Instant giveUp = bookingStartedAt(b).plus(Duration.ofMinutes(config.getPoll().getMaxMinutes()));
        if (b.getStatus() == PlatformBookingStatus.BOOKING && now.isAfter(giveUp)) {
            b.setStatus(PlatformBookingStatus.UNKNOWN);
            events.record(bookingId, "UNKNOWN", Map.of("polls", b.getPollCount()));
            notificationService.platformAlert("Booking " + bookingId + " outcome unknown",
                    "No final answer from the supplier for booking " + bookingId + " (agency " + b.getTenantId() + ") after "
                            + config.getPoll().getMaxMinutes() + " minutes and " + b.getPollCount()
                            + " polls. The wallet hold is kept; check with the supplier and resolve it.");
        }
        bookingRepository.save(b);
    }

    private Instant bookingStartedAt(PlatformBooking b) {
        return eventRepository.findByPlatformBookingIdOrderByCreatedAtAsc(b.getId()).stream()
                .filter(e -> "BOOKING".equals(e.getType()))
                .map(PlatformBookingEvent::getCreatedAt)
                .findFirst()
                .orElse(b.getCreatedAt());
    }
}
