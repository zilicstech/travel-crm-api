package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Feedback;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The tenant side of a feedback link: the page with any earlier answer, and a first submission. */
@ExtendWith(MockitoExtension.class)
class PublicFeedbackTenantServiceCoverageTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private FeedbackRepository feedbackRepository;
    @InjectMocks private PublicFeedbackTenantService service;

    private final Booking booking = Booking.builder().id("B1").destination("Goa").journeyDate(LocalDate.of(2026, 11, 1)).build();

    @Test
    void thePageShowsTheTripAndAnyEarlierAnswer() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(feedbackRepository.findByBookingId("B1")).thenReturn(
                Optional.of(Feedback.builder().rating(4).comment("Nice").build()), Optional.empty());

        PublicFeedbackResponse answered = service.fetchFeedbackPage("B1").orElseThrow();
        assertThat(answered.getDestination()).isEqualTo("Goa");
        assertThat(answered.getJourneyDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(answered.isAlreadySubmitted()).isTrue();
        assertThat(answered.getRating()).isEqualTo(4);
        assertThat(answered.getComment()).isEqualTo("Nice");

        PublicFeedbackResponse fresh = service.fetchFeedbackPage("B1").orElseThrow();
        assertThat(fresh.isAlreadySubmitted()).isFalse();
        assertThat(fresh.getRating()).isNull();
        assertThat(fresh.getComment()).isNull();
    }

    @Test
    void aMissingBookingHasNoPageAndTakesNoSubmission() {
        when(bookingRepository.findById("B9")).thenReturn(Optional.empty());
        assertThat(service.fetchFeedbackPage("B9")).isEmpty();
        assertThat(service.submit("B9", "C1", new FeedbackSubmitRequest())).isFalse();
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void aFirstSubmissionIsSavedForTheBookingAndClient() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking));
        when(feedbackRepository.findByBookingId("B1")).thenReturn(Optional.empty());
        FeedbackSubmitRequest r = new FeedbackSubmitRequest();
        r.setRating(5);
        r.setComment("Lovely trip");

        assertThat(service.submit("B1", "C1", r)).isTrue();

        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo("B1");
        assertThat(saved.getValue().getClientId()).isEqualTo("C1");
        assertThat(saved.getValue().getRating()).isEqualTo(5);
        assertThat(saved.getValue().getComment()).isEqualTo("Lovely trip");
        assertThat(saved.getValue().getSubmittedAt()).isNotNull();
        verify(feedbackRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
