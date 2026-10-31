package com.voyra.crm.service;

import com.voyra.crm.dto.EscalationResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The "needs attention" feed: who sees what, how each row reads, and its HIGH-first, oldest-first order. */
@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private LeadFollowUpRepository leadFollowUpRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ClientInvoiceRepository clientInvoiceRepository;
    @InjectMocks private EscalationService service;

    private static final Set<BookingStatus> EXCLUDED = Set.of(BookingStatus.CANCELLED, BookingStatus.COMPLETED);
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    @Test
    void needsASignedInUser() {
        assertThatThrownBy(() -> service.list()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(leadRepository, leadFollowUpRepository, bookingRepository, clientInvoiceRepository);
    }

    @Test
    void theOwnerSeesEveryRowHighSeverityFirstThenOldestDueDateWithUndatedLast() {
        as("O1", UserType.AGENCY_OWNER);
        Lead escalatedLong = Lead.builder().id("L1").clientName("Client A").destination("Goa")
                .escalationReason("VIP").escalatedAt(LocalDateTime.of(2026, 1, 5, 9, 0)).build();
        Lead escalatedUndated = Lead.builder().id("L2").clientName("Client B").destination("Bali").build();
        when(leadRepository.findByEscalatedTrue()).thenReturn(List.of(escalatedUndated, escalatedLong));
        LeadFollowUp followUp = LeadFollowUp.builder().leadId("L3").note("Call back").clientName("Client C")
                .leadDestination("Kochi").dueDate(today.minusDays(2)).build();
        when(leadFollowUpRepository.findByStatusAndDueDateLessThanOrderByDueDateAsc(FollowUpStatus.OPEN, today))
                .thenReturn(List.of(followUp));
        Booking ticketing = Booking.builder().id("B1").clientName("Client D").destination("Dubai")
                .ticketingDeadline(today.minusDays(3)).deadlineNote("hold expires").build();
        Booking cancellation = Booking.builder().id("B2").clientName("Client E").destination("Paris")
                .ticketingDeadline(today.plusDays(5)).cancellationDeadline(today.minusDays(1)).build();
        Booking noTicketing = Booking.builder().id("B3").clientName("Client F").destination("Rome")
                .cancellationDeadline(today.minusDays(10)).build();
        when(bookingRepository.findByBookingStatusNotInAndTicketingDeadlineLessThan(EXCLUDED, today)).thenReturn(List.of(ticketing));
        when(bookingRepository.findByBookingStatusNotInAndCancellationDeadlineLessThan(EXCLUDED, today))
                .thenReturn(List.of(cancellation, noTicketing));
        ClientInvoice inv = ClientInvoice.builder().id("I1").clientName("Client G").description("Balance")
                .dueDate(today.minusDays(30)).build();
        when(clientInvoiceRepository.findByStatusNotAndDueDateLessThan(InvoiceStatus.PAID, today)).thenReturn(List.of(inv));

        List<EscalationResponse> rows = service.list();

        assertThat(rows).extracting(EscalationResponse::getTargetId)
                .containsExactly("L1", "B3", "B1", "B2", "L2", "I1", "L3");
        EscalationResponse lead = rows.get(0);
        assertThat(lead.getType()).isEqualTo("ESCALATED_LEAD");
        assertThat(lead.getTitle()).isEqualTo("Client A / Goa");
        assertThat(lead.getSubtitle()).isEqualTo("VIP");
        assertThat(lead.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(lead.getTargetType()).isEqualTo("LEAD");
        assertThat(rows.get(4).getDueDate()).isNull();
        EscalationResponse b1 = rows.get(2);
        assertThat(b1.getTitle()).isEqualTo("Ticketing deadline passed: Client D");
        assertThat(b1.getSubtitle()).isEqualTo("Dubai — hold expires");
        assertThat(b1.getDueDate()).isEqualTo(today.minusDays(3));
        assertThat(b1.getTargetType()).isEqualTo("BOOKING");
        EscalationResponse b2 = rows.get(3);
        assertThat(b2.getTitle()).isEqualTo("Cancellation window closed: Client E");
        assertThat(b2.getSubtitle()).isEqualTo("Paris");
        assertThat(b2.getDueDate()).isEqualTo(today.minusDays(1));
        assertThat(rows.get(1).getTitle()).isEqualTo("Cancellation window closed: Client F");
        EscalationResponse invoice = rows.get(5);
        assertThat(invoice.getType()).isEqualTo("OVERDUE_INVOICE");
        assertThat(invoice.getSeverity()).isEqualTo("MEDIUM");
        assertThat(invoice.getTitle()).isEqualTo("Invoice overdue: Client G");
        assertThat(invoice.getSubtitle()).isEqualTo("Balance");
        assertThat(invoice.getTargetType()).isEqualTo("CLIENT_INVOICE");
        EscalationResponse fu = rows.get(6);
        assertThat(fu.getType()).isEqualTo("OVERDUE_FOLLOW_UP");
        assertThat(fu.getTitle()).isEqualTo("Call back");
        assertThat(fu.getSubtitle()).isEqualTo("Client C / Kochi");
        assertThat(fu.getTargetId()).isEqualTo("L3");
    }

    @Test
    void anAgentSeesOnlyTheirOwnRows() {
        as("A1", UserType.AGENT);
        when(leadRepository.findByEscalatedTrueAndCreatedBy("A1")).thenReturn(List.of(
                Lead.builder().id("L1").clientName("Client A").destination("Goa").build()));
        when(leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateLessThanOrderByDueDateAsc("A1", FollowUpStatus.OPEN, today))
                .thenReturn(List.of());
        when(bookingRepository.findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan("A1", EXCLUDED, today))
                .thenReturn(List.of());
        when(bookingRepository.findByAgentIdAndBookingStatusNotInAndCancellationDeadlineLessThan("A1", EXCLUDED, today))
                .thenReturn(List.of());
        when(clientInvoiceRepository.findByAgentIdAndStatusNotAndDueDateLessThan("A1", InvoiceStatus.PAID, today))
                .thenReturn(List.of(ClientInvoice.builder().id("I1").clientName("Client G").dueDate(today.minusDays(1)).build()));

        List<EscalationResponse> rows = service.list();

        assertThat(rows).extracting(EscalationResponse::getTargetId).containsExactly("L1", "I1");
    }
}
