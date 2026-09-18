package com.voyra.crm.service;

import com.voyra.crm.dto.CalendarEventResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-only union over five sources. The interesting behaviour is the agent/owner scoping
 * split (mirrors EscalationService) and that an invalid range fails loudly rather than
 * silently returning nothing.
 */
@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private LeadFollowUpRepository leadFollowUpRepository;
    @Mock
    private ClientInvoiceRepository clientInvoiceRepository;
    @Mock
    private VisaRepository visaRepository;

    private CalendarService calendarService;

    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2026, 9, 30);

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        calendarService = new CalendarService(bookingRepository, leadFollowUpRepository, clientInvoiceRepository, visaRepository);
    }

    private void stubEmptyOwnerWide() {
        lenient().when(bookingRepository.findByJourneyDateBetween(any(), any())).thenReturn(List.of());
        lenient().when(bookingRepository.findByReturnDateBetween(any(), any())).thenReturn(List.of());
        lenient().when(bookingRepository.findByBookingStatusNotInAndTicketingDeadlineLessThan(anyCollection(), any())).thenReturn(List.of());
        lenient().when(leadFollowUpRepository.findByStatusAndDueDateBetweenOrderByDueDateAsc(any(), any(), any())).thenReturn(List.of());
        lenient().when(clientInvoiceRepository.findByDueDateBetween(any(), any())).thenReturn(List.of());
        lenient().when(visaRepository.findByAppointmentDateBetween(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidRange_throws() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> calendarService.list(to, from)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendarService.list(null, to)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void owner_seesAgencyWideUnion() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        stubEmptyOwnerWide();

        Booking departing = Booking.builder().id("B1").clientName("Priya Nair").destination("Bangkok")
                .journeyDate(LocalDate.of(2026, 9, 15)).bookingStatus(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findByJourneyDateBetween(from, to)).thenReturn(List.of(departing));

        LeadFollowUp followUp = LeadFollowUp.builder().leadId("L1").clientName("Arjun Mehta")
                .leadDestination("Dubai").dueDate(LocalDate.of(2026, 9, 10)).note("Chase visa docs")
                .status(FollowUpStatus.OPEN).build();
        when(leadFollowUpRepository.findByStatusAndDueDateBetweenOrderByDueDateAsc(FollowUpStatus.OPEN, from, to))
                .thenReturn(List.of(followUp));

        List<CalendarEventResponse> events = calendarService.list(from, to);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CalendarEventResponse::getType)
                .containsExactlyInAnyOrder("TRIP_DEPARTURE", "FOLLOW_UP");
        verify(bookingRepository).findByJourneyDateBetween(from, to);
        verify(bookingRepository, org.mockito.Mockito.never()).findByAgentIdAndJourneyDateBetween(any(), any(), any());
    }

    @Test
    void agent_seesOnlyOwnScope() {
        authenticateAs("A1", UserType.AGENT);
        lenient().when(bookingRepository.findByAgentIdAndJourneyDateBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(bookingRepository.findByAgentIdAndReturnDateBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(bookingRepository.findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan(any(), anyCollection(), any())).thenReturn(List.of());
        lenient().when(leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateBetweenOrderByDueDateAsc(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(clientInvoiceRepository.findByAgentIdAndDueDateBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(visaRepository.findByAgentIdAndAppointmentDateBetween(any(), any(), any())).thenReturn(List.of());

        List<CalendarEventResponse> events = calendarService.list(from, to);

        assertThat(events).isEmpty();
        verify(bookingRepository).findByAgentIdAndJourneyDateBetween(eq("A1"), eq(from), eq(to));
        verify(bookingRepository, org.mockito.Mockito.never()).findByJourneyDateBetween(any(), any());
    }

    @Test
    void resultIsSortedByDate() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        stubEmptyOwnerWide();

        Visa laterVisa = Visa.builder().id("V1").clientName("Client A").country("France")
                .visaType("Tourist").appointmentDate(LocalDate.of(2026, 9, 25)).build();
        ClientInvoice earlierInvoice = ClientInvoice.builder().id("I1").clientName("Client B")
                .dueDate(LocalDate.of(2026, 9, 5)).build();
        when(visaRepository.findByAppointmentDateBetween(from, to)).thenReturn(List.of(laterVisa));
        when(clientInvoiceRepository.findByDueDateBetween(from, to)).thenReturn(List.of(earlierInvoice));

        List<CalendarEventResponse> events = calendarService.list(from, to);

        assertThat(events).extracting(CalendarEventResponse::getDate)
                .containsExactly(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 25));
    }
}
