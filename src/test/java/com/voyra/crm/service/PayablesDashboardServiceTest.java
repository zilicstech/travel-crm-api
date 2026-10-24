package com.voyra.crm.service;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.SupplierInvoiceLineItemRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.dto.PayablesDashboardSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayablesDashboardServiceTest {

    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierInvoiceLineItemRepository lineItemRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private BookingRepository bookingRepository;
    @InjectMocks private PayablesDashboardService service;

    /** A draft or unapproved bill isn't billed yet, and a payment taken back wasn't paid: neither counts this month. */
    @Test
    void monthFiguresSkipUnpostedBillsAndReversedPayments() {
        SupplierInvoice posted = SupplierInvoice.builder().status(SupplierInvoiceStatus.APPROVED)
                .grandTotalInr(new BigDecimal("1180.00")).gstTotalInr(new BigDecimal("180.00")).build();
        when(supplierInvoiceRepository.findByStatusNotInAndInvoiceDateBetween(
                eq(List.of(SupplierInvoiceStatus.CANCELLED, SupplierInvoiceStatus.DRAFT, SupplierInvoiceStatus.PENDING_APPROVAL)), any(), any()))
                .thenReturn(List.of(posted));
        SupplierPayment kept = payment("500.00", null, null);
        SupplierPayment takenBack = payment("600.00", LocalDateTime.now(), null);
        SupplierPayment reversal = payment("-600.00", null, "P2");
        when(supplierPaymentRepository.findByDirectionAndPaidOnBetween(eq(SupplierPaymentDirection.PAYMENT), any(), any()))
                .thenReturn(List.of(kept, takenBack, reversal));

        PayablesDashboardSummaryResponse r = service.summary();

        assertThat(r.getBilledThisMonthInr()).isEqualByComparingTo("1180.00");
        assertThat(r.getInputTaxThisMonthInr()).isEqualByComparingTo("180.00");
        assertThat(r.getPaidThisMonthInr()).isEqualByComparingTo("500.00");
    }

    private SupplierPayment payment(String amount, LocalDateTime reversedAt, String reverses) {
        return SupplierPayment.builder().direction(SupplierPaymentDirection.PAYMENT).isAdvance(false)
                .amountInr(new BigDecimal(amount)).reversedAt(reversedAt).reversesPaymentId(reverses).build();
    }
}
