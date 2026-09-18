package com.voyra.crm.service;

import com.voyra.crm.dto.EscalationResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * In-app "needs attention" feed - a read-only union of overdue follow-ups, past-deadline
 * bookings, overdue invoices and manually-escalated leads. No new table, no scheduler, no
 * push/email: this is purely a GET that recomputes the list on every call from existing rows.
 */
@Service
@RequiredArgsConstructor
public class EscalationService {

    private static final Set<BookingStatus> DEADLINE_EXCLUDED = Set.of(BookingStatus.CANCELLED, BookingStatus.COMPLETED);

    private final LeadRepository leadRepository;
    private final LeadFollowUpRepository leadFollowUpRepository;
    private final BookingRepository bookingRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;

    @Transactional(readOnly = true)
    public List<EscalationResponse> list() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        String agentId = principal.isAgent() ? principal.userId() : null;
        LocalDate today = LocalDate.now();

        List<EscalationResponse> rows = new ArrayList<>();

        for (Lead lead : agentId != null ? leadRepository.findByEscalatedTrueAndCreatedBy(agentId)
                : leadRepository.findByEscalatedTrue()) {
            rows.add(EscalationResponse.builder()
                    .type("ESCALATED_LEAD").severity("HIGH")
                    .title(lead.getClientName() + " / " + lead.getDestination())
                    .subtitle(lead.getEscalationReason())
                    .dueDate(lead.getEscalatedAt() != null ? lead.getEscalatedAt().toLocalDate() : null)
                    .targetId(lead.getId()).targetType("LEAD")
                    .build());
        }

        for (LeadFollowUp f : agentId != null
                ? leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateLessThanOrderByDueDateAsc(agentId, FollowUpStatus.OPEN, today)
                : leadFollowUpRepository.findByStatusAndDueDateLessThanOrderByDueDateAsc(FollowUpStatus.OPEN, today)) {
            rows.add(EscalationResponse.builder()
                    .type("OVERDUE_FOLLOW_UP").severity("MEDIUM")
                    .title(f.getNote())
                    .subtitle(f.getClientName() + " / " + f.getLeadDestination())
                    .dueDate(f.getDueDate())
                    .targetId(f.getLeadId()).targetType("LEAD")
                    .build());
        }

        List<Booking> deadlineBookings = new ArrayList<>();
        if (agentId != null) {
            deadlineBookings.addAll(bookingRepository.findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan(agentId, DEADLINE_EXCLUDED, today));
            deadlineBookings.addAll(bookingRepository.findByAgentIdAndBookingStatusNotInAndCancellationDeadlineLessThan(agentId, DEADLINE_EXCLUDED, today));
        } else {
            deadlineBookings.addAll(bookingRepository.findByBookingStatusNotInAndTicketingDeadlineLessThan(DEADLINE_EXCLUDED, today));
            deadlineBookings.addAll(bookingRepository.findByBookingStatusNotInAndCancellationDeadlineLessThan(DEADLINE_EXCLUDED, today));
        }
        for (Booking b : deadlineBookings) {
            boolean ticketingPassed = b.getTicketingDeadline() != null && b.getTicketingDeadline().isBefore(today);
            rows.add(EscalationResponse.builder()
                    .type("BOOKING_DEADLINE").severity("HIGH")
                    .title((ticketingPassed ? "Ticketing deadline passed: " : "Cancellation window closed: ") + b.getClientName())
                    .subtitle(b.getDestination() + (b.getDeadlineNote() != null ? " — " + b.getDeadlineNote() : ""))
                    .dueDate(ticketingPassed ? b.getTicketingDeadline() : b.getCancellationDeadline())
                    .targetId(b.getId()).targetType("BOOKING")
                    .build());
        }

        for (ClientInvoice inv : agentId != null
                ? clientInvoiceRepository.findByAgentIdAndStatusNotAndDueDateLessThan(agentId, InvoiceStatus.PAID, today)
                : clientInvoiceRepository.findByStatusNotAndDueDateLessThan(InvoiceStatus.PAID, today)) {
            rows.add(EscalationResponse.builder()
                    .type("OVERDUE_INVOICE").severity("MEDIUM")
                    .title("Invoice overdue: " + inv.getClientName())
                    .subtitle(inv.getDescription())
                    .dueDate(inv.getDueDate())
                    .targetId(inv.getId()).targetType("CLIENT_INVOICE")
                    .build());
        }

        return rows.stream()
                .sorted(Comparator.comparing((EscalationResponse r) -> "HIGH".equals(r.getSeverity()) ? 0 : 1)
                        .thenComparing(EscalationResponse::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
