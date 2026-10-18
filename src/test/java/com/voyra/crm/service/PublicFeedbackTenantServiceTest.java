package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Feedback;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicFeedbackTenantServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private FeedbackRepository feedbackRepository;
    @InjectMocks private PublicFeedbackTenantService service;

    /** Anyone holding the link must not be able to rewrite a submitted rating. */
    @Test
    void aSecondSubmissionIsRefused() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").build()));
        when(feedbackRepository.findByBookingId("B1")).thenReturn(Optional.of(Feedback.builder().id("F1").rating(4).build()));
        FeedbackSubmitRequest request = new FeedbackSubmitRequest();
        request.setRating(1);

        assertThatThrownBy(() -> service.submit("B1", "C1", request)).isInstanceOf(IllegalStateException.class);
        verify(feedbackRepository, never()).save(any());
    }
}
