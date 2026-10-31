package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackLinkResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.FeedbackLinkRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackLinkServiceTest {

    @Mock private BookingService bookingService;
    @Mock private FeedbackLinkRepository feedbackLinkRepository;
    @InjectMocks private FeedbackLinkService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "publicFeedbackBaseUrl", "https://app.example.test/feedback");
        ReflectionTestUtils.setField(service, "validityDays", 30);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "u", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void booking(BookingStatus status) {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(Booking.builder().id("B1").clientId("C1").bookingStatus(status).build());
    }

    @Test
    void feedbackWaitsForTheTripToBeCompleted() {
        booking(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.generateLink("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Feedback can be requested only after the trip is completed.");
        verify(feedbackLinkRepository, never()).save(any());
    }

    @Test
    void aLiveLinkIsReusedRatherThanMintedAgain() {
        booking(BookingStatus.COMPLETED);
        when(feedbackLinkRepository.findFirstByBookingIdAndExpiresAtAfterOrderByExpiresAtDesc(eq("B1"), any()))
                .thenReturn(Optional.of(FeedbackLink.builder().token("existing").build()));

        FeedbackLinkResponse r = service.generateLink("B1");

        assertThat(r.getToken()).isEqualTo("existing");
        assertThat(r.getUrl()).isEqualTo("https://app.example.test/feedback/existing");
        verify(feedbackLinkRepository, never()).save(any());
    }

    @Test
    void aNewLinkSkipsTakenTokensAndIsKeyedToTheAgencyAndClient() {
        booking(BookingStatus.COMPLETED);
        when(feedbackLinkRepository.existsById(anyString())).thenReturn(true, false);

        FeedbackLinkResponse r = service.generateLink("B1");

        ArgumentCaptor<FeedbackLink> saved = ArgumentCaptor.forClass(FeedbackLink.class);
        verify(feedbackLinkRepository).save(saved.capture());
        assertThat(saved.getValue().getToken()).isEqualTo(r.getToken()).hasSize(32);
        assertThat(saved.getValue().getTenantId()).isEqualTo("T1");
        assertThat(saved.getValue().getBookingId()).isEqualTo("B1");
        assertThat(saved.getValue().getClientId()).isEqualTo("C1");
        assertThat(saved.getValue().getExpiresAt()).isCloseTo(LocalDateTime.now().plusDays(30), within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(r.getUrl()).isEqualTo("https://app.example.test/feedback/" + r.getToken());
    }

    @Test
    void tenCollisionsInARowGiveUp() {
        booking(BookingStatus.COMPLETED);
        when(feedbackLinkRepository.existsById(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.generateLink("B1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unable to generate unique feedback token after 10 attempts");
        verify(feedbackLinkRepository, org.mockito.Mockito.times(10)).existsById(anyString());
    }
}
