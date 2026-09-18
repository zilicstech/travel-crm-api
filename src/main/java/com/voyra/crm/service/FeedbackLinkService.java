package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackLinkResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.repository.FeedbackLinkRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Generates the unguessable public share-link token for requesting feedback on a booking.
 * Same shape as ProposalLinkService, keyed on the booking rather than the lead - see
 * PublicFeedbackService for the resolution side.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackLinkService {

    private final BookingService bookingService;
    private final FeedbackLinkRepository feedbackLinkRepository;

    @Value("${app.public-feedback.base-url}")
    private String publicFeedbackBaseUrl;

    @Value("${app.public-feedback.validity-days:90}")
    private int validityDays;

    @Transactional
    public FeedbackLinkResponse generateLink(String bookingId) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();

        String token = feedbackLinkRepository
                .findFirstByBookingIdAndExpiresAtAfterOrderByExpiresAtDesc(bookingId, LocalDateTime.now())
                .map(FeedbackLink::getToken)
                .orElse(null);

        if (token == null) {
            token = generateUniqueToken();
            feedbackLinkRepository.save(FeedbackLink.builder()
                    .token(token)
                    .tenantId(tenantId)
                    .bookingId(bookingId)
                    .clientId(booking.getClientId())
                    .expiresAt(LocalDateTime.now().plusDays(validityDays))
                    .build());
            log.info("Feedback link generated: bookingId={}", bookingId);
        }

        return FeedbackLinkResponse.builder()
                .token(token)
                .url(publicFeedbackBaseUrl + "/" + token)
                .build();
    }

    private String generateUniqueToken() {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String token = IdGenerator.generateToken32();
            if (!feedbackLinkRepository.existsById(token)) {
                return token;
            }
        }
        throw new IllegalStateException("Unable to generate unique feedback token after " + maxAttempts + " attempts");
    }
}
