package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gate 2.1-adjacent: posting-rule-table row 7, the job that moves 2120 -> 40«cat» on departure. */
@ExtendWith(MockitoExtension.class)
class RevenueRecognitionTenantServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalService journalService;

    @InjectMocks
    private RevenueRecognitionTenantService service;

    @Test
    void recognizesADeferredInvoiceWhoseBookingHasDeparted() {
        LocalDate asOf = LocalDate.now();
        Booking arrived = Booking.builder().id("B1").departureDate(asOf.minusDays(2)).build();
        when(bookingRepository.findByDepartureDateLessThanEqual(asOf)).thenReturn(List.of(arrived));

        Invoice invoice = Invoice.builder()
                .id("I1").invoiceNumber("PKG/2026-27/0001").bookingId("B1")
                .status(InvoiceLifecycle.ISSUED).serviceCategory(InvoiceServiceCategory.PACKAGE)
                .taxableValueInr(new BigDecimal("50000.00"))
                .build();
        when(invoiceRepository.findByBookingIdInAndStatusIn(List.of("B1"),
                List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID, InvoiceLifecycle.PAID)))
                .thenReturn(List.of(invoice));
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.REVENUE_RECOGNIZED)).thenReturn(false);
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED)).thenReturn(true);

        RevenueRecognitionTenantService.RecognitionResult result = service.recognizeDue(asOf);

        assertThat(result.eligible()).isEqualTo(1);
        assertThat(result.recognized()).isEqualTo(1);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();
        assertThat(posting.purpose()).isEqualTo(JournalPurpose.REVENUE_RECOGNIZED);
        assertThat(posting.sourceType()).isEqualTo(JournalSourceType.INVOICE);
        assertThat(posting.sourceId()).isEqualTo("I1");
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2120");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("50000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("4070"); // PACKAGE is index 6 -> (6+1)*10
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    void anAlreadyRecognizedInvoiceIsSkippedOnARerun() {
        LocalDate asOf = LocalDate.now();
        Booking arrived = Booking.builder().id("B1").departureDate(asOf.minusDays(2)).build();
        when(bookingRepository.findByDepartureDateLessThanEqual(asOf)).thenReturn(List.of(arrived));

        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED)
                .serviceCategory(InvoiceServiceCategory.PACKAGE).taxableValueInr(new BigDecimal("50000.00")).build();
        when(invoiceRepository.findByBookingIdInAndStatusIn(anyList(), anyList())).thenReturn(List.of(invoice));
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.REVENUE_RECOGNIZED)).thenReturn(true);

        RevenueRecognitionTenantService.RecognitionResult result = service.recognizeDue(asOf);

        assertThat(result.recognized()).isEqualTo(0);
        verify(journalService, never()).post(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aCommissionAgentInvoiceWithNoDeferredEntryIsNeverRecognized() {
        LocalDate asOf = LocalDate.now();
        Booking arrived = Booking.builder().id("B1").departureDate(asOf.minusDays(2)).build();
        when(bookingRepository.findByDepartureDateLessThanEqual(asOf)).thenReturn(List.of(arrived));

        Invoice invoice = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.ISSUED)
                .serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL).taxableValueInr(new BigDecimal("83000.00")).build();
        when(invoiceRepository.findByBookingIdInAndStatusIn(anyList(), anyList())).thenReturn(List.of(invoice));
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.REVENUE_RECOGNIZED)).thenReturn(false);
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED)).thenReturn(false);

        RevenueRecognitionTenantService.RecognitionResult result = service.recognizeDue(asOf);

        assertThat(result.recognized()).isEqualTo(0);
        verify(journalService, never()).post(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noBookingsHaveDepartedYieldsAnEmptyNoOpResult() {
        LocalDate asOf = LocalDate.now();
        when(bookingRepository.findByDepartureDateLessThanEqual(asOf)).thenReturn(List.of());

        RevenueRecognitionTenantService.RecognitionResult result = service.recognizeDue(asOf);

        assertThat(result.eligible()).isEqualTo(0);
        assertThat(result.recognized()).isEqualTo(0);
        verify(invoiceRepository, never()).findByBookingIdInAndStatusIn(anyList(), anyList());
    }
}
