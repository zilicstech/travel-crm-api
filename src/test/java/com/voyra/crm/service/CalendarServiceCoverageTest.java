package com.voyra.crm.service;

import com.voyra.crm.dto.CalendarEventResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.LeadFollowUpRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class CalendarServiceCoverageTest {

    @Mock BookingRepository bookingRepository;
    @Mock LeadFollowUpRepository leadFollowUpRepository;
    @Mock ClientInvoiceRepository clientInvoiceRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock VisaRepository visaRepository;
    @Mock SupplierInvoiceRepository supplierInvoiceRepository;
    @InjectMocks CalendarService service;

    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2026, 9, 30);
    private final List<InvoiceLifecycle> open = List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    @Test
    void aMissingEndDateIsRefused() {
        assertThatThrownBy(() -> service.list(from, null)).hasMessage("A valid from/to date range is required");
    }

    @Test
    void theOwnerSeesReturnsAccountsInvoicesDeadlinesInRangeAndVisaAppointments() {
        signInAs("O1", UserType.AGENCY_OWNER);
        when(bookingRepository.findByJourneyDateBetween(from, to)).thenReturn(List.of(
                Booking.builder().id("B1").clientName("Asha").destination("Goa").journeyDate(LocalDate.of(2026, 9, 5)).build(),
                Booking.builder().id("B7").clientName("Ira").destination("Rome").type(BookingType.HOTEL).journeyDate(LocalDate.of(2026, 9, 6)).build()));
        when(bookingRepository.findByReturnDateBetween(from, to)).thenReturn(List.of(
                Booking.builder().id("B2").clientName("Ravi").destination("Dubai").type(BookingType.FLIGHT).returnDate(LocalDate.of(2026, 9, 9)).build(),
                Booking.builder().id("B3").clientName("Meera").destination("Bali").returnDate(LocalDate.of(2026, 9, 8)).build()));
        when(invoiceRepository.findByDocumentTypeAndStatusInAndDueDateBetween(InvoiceDocumentType.TAX_INVOICE, open, from, to)).thenReturn(List.of(
                Invoice.builder().id("I1").clientName("Asha").invoiceNumber("HTL/1").agentId("A2").bookingId("B1")
                        .balanceDueInr(new BigDecimal("500.00")).dueDate(LocalDate.of(2026, 9, 12)).build()));
        when(bookingRepository.findByBookingStatusNotInAndTicketingDeadlineLessThan(anyCollection(), eq(to.plusDays(1)))).thenReturn(List.of(
                Booking.builder().id("B4").clientName("Old").build(),
                Booking.builder().id("B5").clientName("Earlier").ticketingDeadline(LocalDate.of(2026, 8, 31)).build(),
                Booking.builder().id("B6").clientName("Soon").destination("Delhi").ticketingDeadline(LocalDate.of(2026, 9, 1)).build()));
        when(visaRepository.findByAppointmentDateBetween(from, to)).thenReturn(List.of(
                Visa.builder().id("V1").clientName("Asha").country("France").visaType("Tourist").appointmentDate(LocalDate.of(2026, 9, 20)).build()));

        List<CalendarEventResponse> events = service.list(from, to);

        assertThat(events).extracting(CalendarEventResponse::getTargetId).containsExactly("B6", "B1", "B7", "B3", "B2", "I1", "V1");
        assertThat(events.get(0).getType()).isEqualTo("BOOKING_DEADLINE");
        assertThat(events.get(0).getTitle()).isEqualTo("Ticketing deadline: Soon");
        assertThat(events.get(1).getSubtitle()).isNull();
        assertThat(events.get(2).getSubtitle()).isEqualTo("HOTEL");
        assertThat(events.get(2).getTitle()).isEqualTo("Ira departs for Rome");
        assertThat(events.get(3).getType()).isEqualTo("TRIP_RETURN");
        assertThat(events.get(3).getSubtitle()).isNull();
        assertThat(events.get(4).getSubtitle()).isEqualTo("FLIGHT");
        assertThat(events.get(4).getTitle()).isEqualTo("Ravi returns from Dubai");
        assertThat(events.get(5).getAmount()).isEqualByComparingTo("500.00");
        assertThat(events.get(5).getBookingId()).isEqualTo("B1");
        assertThat(events.get(5).getSubtitle()).isEqualTo("HTL/1");
        assertThat(events.get(6).getTitle()).isEqualTo("Asha — France visa appointment");
        assertThat(events.get(6).getType()).isEqualTo("VISA_APPOINTMENT");
    }

    @Test
    void anAgentSeesOnlyTheirOwnAccountsInvoicesReturnsAndDeadlinesAndNoSupplierBills() {
        signInAs("A1", UserType.AGENT);
        when(bookingRepository.findByAgentIdAndReturnDateBetween("A1", from, to)).thenReturn(List.of(
                Booking.builder().id("B2").clientName("Ravi").destination("Dubai").returnDate(LocalDate.of(2026, 9, 9)).build()));
        when(invoiceRepository.findByDocumentTypeAndStatusInAndDueDateBetween(InvoiceDocumentType.TAX_INVOICE, open, from, to)).thenReturn(List.of(
                Invoice.builder().id("I1").agentId("A2").dueDate(LocalDate.of(2026, 9, 12)).build(),
                Invoice.builder().id("I2").agentId("A1").clientName("Asha").dueDate(LocalDate.of(2026, 9, 13)).build()));
        when(bookingRepository.findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan(eq("A1"), anyCollection(), eq(to.plusDays(1))))
                .thenReturn(List.of(Booking.builder().id("B9").clientName("Soon").ticketingDeadline(LocalDate.of(2026, 9, 2)).build()));

        List<CalendarEventResponse> events = service.list(from, to);

        assertThat(events).extracting(CalendarEventResponse::getTargetId).containsExactly("B9", "B2", "I2");
        verify(supplierInvoiceRepository, never()).findByDueDateBetweenAndBalanceDueGreaterThan(any(), any(), any());
    }
}
