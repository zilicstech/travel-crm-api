package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Feedback;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A client's feedback, newest first, each named by its booking's destination. */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock private FeedbackRepository feedbackRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ClientService clientService;
    @InjectMocks private FeedbackService service;

    @Test
    void listsFeedbackWithItsBookingsDestination() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 2, 9, 0);
        when(feedbackRepository.findByClientIdOrderBySubmittedAtDesc("C1")).thenReturn(List.of(
                Feedback.builder().id("F1").bookingId("B1").rating(5).comment("Great").submittedAt(at).build(),
                Feedback.builder().id("F2").bookingId("B9").rating(3).build()));
        when(bookingRepository.findByClientId("C1")).thenReturn(List.of(Booking.builder().id("B1").destination("Goa").build()));

        List<FeedbackResponse> out = service.listForClient("C1");

        assertThat(out.get(0).getBookingDestination()).isEqualTo("Goa");
        assertThat(out.get(0).getRating()).isEqualTo(5);
        assertThat(out.get(0).getComment()).isEqualTo("Great");
        assertThat(out.get(0).getSubmittedAt()).isEqualTo(at);
        assertThat(out.get(1).getBookingDestination()).isNull();
        assertThat(out.get(1).getId()).isEqualTo("F2");
    }

    @Test
    void anUnknownClientIsRefusedBeforeAnyRead() {
        when(clientService.findAccessibleClient("C9")).thenThrow(new IllegalArgumentException("Client not found: C9"));
        assertThatThrownBy(() -> service.listForClient("C9")).hasMessage("Client not found: C9");
        verifyNoInteractions(feedbackRepository, bookingRepository);
    }
}
