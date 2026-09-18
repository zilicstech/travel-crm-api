package com.voyra.crm.service;

import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Feedback;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.FeedbackRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Tenant-scoped work for the unauthenticated public feedback endpoints. Runs in a fresh
 * REQUIRES_NEW transaction acquired AFTER {@link PublicFeedbackService} sets
 * {@link com.voyra.crm.context.TenantContext} (blueprint §3.5) - must live on its own bean,
 * never called via self-invocation. The response DTO is built HERE so a pricing/internal
 * field can never leak by being serialized from a raw entity later.
 */
@Service
@RequiredArgsConstructor
public class PublicFeedbackTenantService {

    private final BookingRepository bookingRepository;
    private final FeedbackRepository feedbackRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PublicFeedbackResponse> fetchFeedbackPage(String bookingId) {
        return bookingRepository.findById(bookingId).map(booking -> {
            Optional<Feedback> existing = feedbackRepository.findByBookingId(bookingId);
            return PublicFeedbackResponse.builder()
                    .destination(booking.getDestination())
                    .journeyDate(booking.getJourneyDate())
                    .alreadySubmitted(existing.isPresent())
                    .rating(existing.map(Feedback::getRating).orElse(null))
                    .comment(existing.map(Feedback::getComment).orElse(null))
                    .build();
        });
    }

    /** Idempotent: resubmitting overwrites the same row rather than accumulating history. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean submit(String bookingId, String clientId, FeedbackSubmitRequest request) {
        Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
        if (bookingOpt.isEmpty()) {
            return false;
        }
        Feedback feedback = feedbackRepository.findByBookingId(bookingId)
                .orElseGet(() -> Feedback.builder()
                        .id(UniqueIdResolver.resolve(feedbackRepository::existsById))
                        .bookingId(bookingId)
                        .clientId(clientId)
                        .build());
        feedback.setRating(request.getRating());
        feedback.setComment(request.getComment());
        feedback.setSubmittedAt(LocalDateTime.now());
        feedbackRepository.save(feedback);
        return true;
    }
}
