package com.voyra.crm.service;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pins the old sales-pipeline revenue figure (unfiltered {@code SUM(booking.sellingPrice)}, as it
 * existed before this epic) against the new one (the same sum with cancelled bookings excluded -
 * {@code BookingRepository}'s queries) and against the accounting-grade "true revenue" figure
 * ({@link AccountsDashboardService#trueRevenue}), and shows the delta between the first and the
 * last is explained entirely by a cancelled booking plus a credit note - exactly the epic's
 * acceptance criterion. The pipeline-side numbers are computed by hand here (the actual JPQL fix
 * in {@code BookingRepository} is a one-line filter, verified live against real Postgres data
 * rather than re-implemented against an in-memory DB this test would otherwise need) so this test
 * documents and locks in the relationship rather than exercising the query engine itself.
 */
@ExtendWith(MockitoExtension.class)
class RevenueDefinitionTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private CreditNoteRepository creditNoteRepository;

    @InjectMocks
    private AccountsDashboardService accountsDashboardService;

    @Test
    void deltaBetweenOldAndTrueRevenueIsExactlyTheCancelledBookingPlusTheCreditNote() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        Booking billedA = Booking.builder().id("B-A").bookingStatus(BookingStatus.CONFIRMED)
                .sellingPrice(new BigDecimal("100000")).build();
        Booking cancelled = Booking.builder().id("B-B").bookingStatus(BookingStatus.CANCELLED)
                .sellingPrice(new BigDecimal("50000")).build();
        Booking billedC = Booking.builder().id("B-C").bookingStatus(BookingStatus.CONFIRMED)
                .sellingPrice(new BigDecimal("80000")).build();
        List<Booking> allBookings = List.of(billedA, cancelled, billedC);

        // The old, pre-epic pipeline figure: every booking counted, cancelled included.
        BigDecimal oldPipelineRevenue = allBookings.stream()
                .map(Booking::getSellingPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The fixed pipeline figure: BookingRepository's queries now filter bookingStatus <> CANCELLED.
        BigDecimal newPipelineRevenue = allBookings.stream()
                .filter(b -> b.getBookingStatus() != BookingStatus.CANCELLED)
                .map(Booking::getSellingPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(oldPipelineRevenue).isEqualByComparingTo("230000");
        assertThat(newPipelineRevenue).isEqualByComparingTo("180000");
        assertThat(oldPipelineRevenue.subtract(newPipelineRevenue)).isEqualByComparingTo(cancelled.getSellingPrice());

        // The accounting-grade "true revenue": taxable value of issued invoices for A and C
        // (0% tax in this fixture so taxable value == sellingPrice, for a clean comparison),
        // net of a credit note issued against C's invoice.
        Invoice invoiceA = Invoice.builder().id("I-A").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).taxableValueInr(new BigDecimal("100000")).build();
        Invoice invoiceC = Invoice.builder().id("I-C").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).taxableValueInr(new BigDecimal("80000")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(
                InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED, from, to))
                .thenReturn(List.of(invoiceA, invoiceC));

        CreditNote creditNoteOnC = CreditNote.builder().id("CN-C").status(CreditNoteStatus.ISSUED)
                .taxableValue(new BigDecimal("20000")).fxRateToInr(BigDecimal.ONE).build();
        when(creditNoteRepository.findByStatusAndNoteDateBetween(CreditNoteStatus.ISSUED, from, to))
                .thenReturn(List.of(creditNoteOnC));

        BigDecimal trueRevenue = accountsDashboardService.trueRevenue(from, to);

        assertThat(trueRevenue).isEqualByComparingTo("160000"); // (100000 + 80000) - 20000

        // The epic's acceptance criterion: old pipeline vs. true revenue, delta explained
        // entirely by the cancelled booking plus the credit note.
        BigDecimal delta = oldPipelineRevenue.subtract(trueRevenue);
        BigDecimal explainedByCancelledBookingsAndCreditNotes = cancelled.getSellingPrice().add(creditNoteOnC.getTaxableValue());
        assertThat(delta).isEqualByComparingTo(explainedByCancelledBookingsAndCreditNotes);
        assertThat(delta).isEqualByComparingTo("70000");
    }
}
