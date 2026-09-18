package com.voyra.crm.service;

import com.voyra.crm.dto.CalendarEventResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.LeadFollowUp;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.VisaRepository;
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
 * Agency-wide (or agent-scoped) calendar - a read-only union of trip departures/returns,
 * follow-up due dates, invoice due dates, booking deadlines and visa appointments over a
 * date range. Recomputed on every call from existing rows, same shape as EscalationService;
 * no new table, no scheduler.
 */
@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final Set<BookingStatus> DEADLINE_EXCLUDED = Set.of(BookingStatus.CANCELLED, BookingStatus.COMPLETED);

    private final BookingRepository bookingRepository;
    private final LeadFollowUpRepository leadFollowUpRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;
    private final VisaRepository visaRepository;

    @Transactional(readOnly = true)
    public List<CalendarEventResponse> list(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A valid from/to date range is required");
        }
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        String agentId = principal.isAgent() ? principal.userId() : null;

        List<CalendarEventResponse> rows = new ArrayList<>();

        List<Booking> departing = agentId != null
                ? bookingRepository.findByAgentIdAndJourneyDateBetween(agentId, from, to)
                : bookingRepository.findByJourneyDateBetween(from, to);
        for (Booking b : departing) {
            rows.add(CalendarEventResponse.builder()
                    .type("TRIP_DEPARTURE").severity("MEDIUM")
                    .date(b.getJourneyDate())
                    .title(b.getClientName() + " departs for " + b.getDestination())
                    .subtitle(b.getType() != null ? b.getType().toString() : null)
                    .targetId(b.getId()).targetType("BOOKING")
                    .build());
        }

        List<Booking> returning = agentId != null
                ? bookingRepository.findByAgentIdAndReturnDateBetween(agentId, from, to)
                : bookingRepository.findByReturnDateBetween(from, to);
        for (Booking b : returning) {
            rows.add(CalendarEventResponse.builder()
                    .type("TRIP_RETURN").severity("MEDIUM")
                    .date(b.getReturnDate())
                    .title(b.getClientName() + " returns from " + b.getDestination())
                    .subtitle(b.getType() != null ? b.getType().toString() : null)
                    .targetId(b.getId()).targetType("BOOKING")
                    .build());
        }

        List<LeadFollowUp> followUps = agentId != null
                ? leadFollowUpRepository.findByAssignedAgentIdAndStatusAndDueDateBetweenOrderByDueDateAsc(
                        agentId, FollowUpStatus.OPEN, from, to)
                : leadFollowUpRepository.findByStatusAndDueDateBetweenOrderByDueDateAsc(FollowUpStatus.OPEN, from, to);
        for (LeadFollowUp f : followUps) {
            rows.add(CalendarEventResponse.builder()
                    .type("FOLLOW_UP").severity("MEDIUM")
                    .date(f.getDueDate())
                    .title(f.getNote())
                    .subtitle(f.getClientName() + " / " + f.getLeadDestination())
                    .targetId(f.getLeadId()).targetType("LEAD")
                    .build());
        }

        List<ClientInvoice> invoicesDue = agentId != null
                ? clientInvoiceRepository.findByAgentIdAndDueDateBetween(agentId, from, to)
                : clientInvoiceRepository.findByDueDateBetween(from, to);
        for (ClientInvoice inv : invoicesDue) {
            rows.add(CalendarEventResponse.builder()
                    .type("INVOICE_DUE").severity("MEDIUM")
                    .date(inv.getDueDate())
                    .title("Invoice due: " + inv.getClientName())
                    .subtitle(inv.getDescription())
                    .targetId(inv.getId()).targetType("CLIENT_INVOICE")
                    .build());
        }

        LocalDate toExclusive = to.plusDays(1);
        List<Booking> ticketingDeadlines = agentId != null
                ? bookingRepository.findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan(agentId, DEADLINE_EXCLUDED, toExclusive)
                : bookingRepository.findByBookingStatusNotInAndTicketingDeadlineLessThan(DEADLINE_EXCLUDED, toExclusive);
        for (Booking b : ticketingDeadlines) {
            if (b.getTicketingDeadline() == null || b.getTicketingDeadline().isBefore(from)) {
                continue;
            }
            rows.add(CalendarEventResponse.builder()
                    .type("BOOKING_DEADLINE").severity("HIGH")
                    .date(b.getTicketingDeadline())
                    .title("Ticketing deadline: " + b.getClientName())
                    .subtitle(b.getDestination())
                    .targetId(b.getId()).targetType("BOOKING")
                    .build());
        }

        List<Visa> appointments = agentId != null
                ? visaRepository.findByAgentIdAndAppointmentDateBetween(agentId, from, to)
                : visaRepository.findByAppointmentDateBetween(from, to);
        for (Visa v : appointments) {
            rows.add(CalendarEventResponse.builder()
                    .type("VISA_APPOINTMENT").severity("MEDIUM")
                    .date(v.getAppointmentDate())
                    .title(v.getClientName() + " — " + v.getCountry() + " visa appointment")
                    .subtitle(v.getVisaType())
                    .targetId(v.getId()).targetType("VISA")
                    .build());
        }

        return rows.stream()
                .sorted(Comparator.comparing(CalendarEventResponse::getDate))
                .toList();
    }
}
