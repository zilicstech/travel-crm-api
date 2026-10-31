package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.models.JournalLinePosting;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueRecognitionTenantServiceCoverageTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalService journalService;
    @InjectMocks private RevenueRecognitionTenantService service;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private void deferred(String invoiceId, boolean deferred, boolean recognized) {
        when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, invoiceId, JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(deferred);
        if (deferred) {
            when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, invoiceId, JournalPurpose.REVENUE_RECOGNIZED))
                    .thenReturn(recognized);
        }
    }

    private JournalPosting posted() {
        ArgumentCaptor<JournalPosting> p = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(p.capture());
        return p.getValue();
    }

    @Test
    void anInvoiceWithoutACategoryIsRecognisedAsMiscellaneousAndAZeroOneIsSkipped() {
        when(bookingRepository.findByDepartureDateLessThanEqual(DAY)).thenReturn(List.of(Booking.builder().id("B1").build()));
        Invoice misc = Invoice.builder().id("I1").invoiceNumber("MSC1").bookingId("B1").taxableValueInr(new BigDecimal("900")).build();
        Invoice zero = Invoice.builder().id("I2").taxableValueInr(BigDecimal.ZERO).build();
        Invoice none = Invoice.builder().id("I3").taxableValueInr(null).build();
        when(invoiceRepository.findByBookingIdInAndStatusIn(anyList(), anyList())).thenReturn(List.of(misc, zero, none));
        for (String id : List.of("I1", "I2", "I3")) {
            when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, id, JournalPurpose.REVENUE_RECOGNIZED)).thenReturn(false);
            when(journalEntryRepository.existsBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, id, JournalPurpose.INVOICE_RAISED_DEFERRED)).thenReturn(true);
        }

        RevenueRecognitionTenantService.RecognitionResult r = service.recognizeDue(DAY);

        assertThat(r.eligible()).isEqualTo(3);
        assertThat(r.recognized()).isEqualTo(1);
        String narration = "Revenue recognized on departure for invoice MSC1";
        assertThat(posted().lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.UNEARNED_TOUR_REVENUE.code(), new BigDecimal("900"), narration),
                JournalLinePosting.credit(SystemAccount.salesCode(InvoiceServiceCategory.MISCELLANEOUS), new BigDecimal("900"), narration));
    }

    @Test
    void whatIsKeptOnACancellationIsRecognisedAtTheInvoicesRate() {
        Invoice invoice = Invoice.builder().id("I1").invoiceNumber("HTL1").bookingId("B1").branchId("BR1")
                .serviceCategory(InvoiceServiceCategory.HOTEL).taxableValue(new BigDecimal("100")).fxRateToInr(new BigDecimal("83")).build();
        deferred("I1", true, false);

        service.recognizeRetainedOnCancellation(invoice, new BigDecimal("40"), DAY);

        JournalPosting p = posted();
        String narration = "Revenue kept on cancellation, invoice HTL1";
        assertThat(p.purpose()).isEqualTo(JournalPurpose.REVENUE_RECOGNIZED);
        assertThat(p.entryDate()).isEqualTo(DAY);
        assertThat(p.branchId()).isEqualTo("BR1");
        assertThat(p.lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.UNEARNED_TOUR_REVENUE.code(), new BigDecimal("4980.00"), narration),
                JournalLinePosting.credit(SystemAccount.salesCode(InvoiceServiceCategory.HOTEL), new BigDecimal("4980.00"), narration));
    }

    @Test
    void aBaseInvoiceWithoutACategoryKeepsItsRetainedRevenueAsMiscellaneous() {
        Invoice invoice = Invoice.builder().id("I1").invoiceNumber("X1").taxableValue(new BigDecimal("100")).fxRateToInr(null).build();
        deferred("I1", true, false);

        service.recognizeRetainedOnCancellation(invoice, new BigDecimal("30"), DAY);

        assertThat(posted().lines().get(1)).isEqualTo(JournalLinePosting.credit(
                SystemAccount.salesCode(InvoiceServiceCategory.MISCELLANEOUS), new BigDecimal("70.00"), "Revenue kept on cancellation, invoice X1"));
    }

    @Test
    void nothingIsRecognisedForAnUndeferredOrAlreadyRecognisedOrFullyCreditedInvoice() {
        Invoice notDeferred = Invoice.builder().id("I1").taxableValue(new BigDecimal("100")).build();
        Invoice recognised = Invoice.builder().id("I2").taxableValue(new BigDecimal("100")).build();
        Invoice credited = Invoice.builder().id("I3").taxableValue(new BigDecimal("100")).build();
        deferred("I1", false, false);
        deferred("I2", true, true);
        deferred("I3", true, false);

        service.recognizeRetainedOnCancellation(notDeferred, BigDecimal.ZERO, DAY);
        service.recognizeRetainedOnCancellation(recognised, BigDecimal.ZERO, DAY);
        service.recognizeRetainedOnCancellation(credited, new BigDecimal("100"), DAY);

        verifyNoInteractions(journalService);
    }
}
