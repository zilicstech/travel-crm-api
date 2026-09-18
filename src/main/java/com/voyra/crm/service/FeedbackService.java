package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Feedback;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Authenticated read side of customer feedback - surfaced on the Client 360 tab. */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final BookingRepository bookingRepository;
    private final ClientService clientService;

    @Transactional(readOnly = true)
    public List<FeedbackResponse> listForClient(String clientId) {
        clientService.findAccessibleClient(clientId);
        List<Feedback> entries = feedbackRepository.findByClientIdOrderBySubmittedAtDesc(clientId);
        Map<String, String> destinationByBookingId = bookingRepository.findByClientId(clientId).stream()
                .collect(Collectors.toMap(Booking::getId, Booking::getDestination));
        return entries.stream()
                .map(f -> FeedbackResponse.builder()
                        .id(f.getId()).bookingId(f.getBookingId())
                        .bookingDestination(destinationByBookingId.get(f.getBookingId()))
                        .rating(f.getRating()).comment(f.getComment()).submittedAt(f.getSubmittedAt())
                        .build())
                .toList();
    }
}
