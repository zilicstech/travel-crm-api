package com.voyra.crm.service;

import com.voyra.crm.dto.AccountsDashboardSummaryResponse;
import com.voyra.crm.dto.GstSummaryRowResponse;
import com.voyra.crm.dto.TcsSummaryRowResponse;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.InvoiceTaxRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountsDashboardServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceTaxRepository invoiceTaxRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private CreditNoteRepository creditNoteRepository;

    @InjectMocks
    private AccountsDashboardService accountsDashboardService;

    private Invoice invoice(BigDecimal grandTotalInr, BigDecimal gstTotalInr, BigDecimal balanceDueInr, LocalDate dueDate) {
        return Invoice.builder().id("I1").clientId("K1").clientName("Arjun Mehta")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotalInr(grandTotalInr).gstTotalInr(gstTotalInr).balanceDueInr(balanceDueInr)
                .dueDate(dueDate).invoiceDate(LocalDate.now())
                .build();
    }

    @Test
    void summaryAggregatesBilledCollectedOutstandingAndOverdue() {
        LocalDate today = LocalDate.now();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(invoice(new BigDecimal("1000.00"), new BigDecimal("50.00"), BigDecimal.ZERO, today.plusDays(5))));
        when(paymentReceiptRepository.findByDirectionAndReceivedOnBetween(eq(ReceiptDirection.RECEIPT), any(), any()))
                .thenReturn(List.of(PaymentReceipt.builder().amountInr(new BigDecimal("400.00")).build()));
        when(invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(any(), any(), any()))
                .thenReturn(List.of(invoice(new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("600.00"), today.minusDays(3))));
        when(paymentReceiptRepository.findByDirectionAndIsAdvanceTrue(ReceiptDirection.RECEIPT)).thenReturn(List.of());

        AccountsDashboardSummaryResponse summary = accountsDashboardService.summary();

        assertThat(summary.getBilledThisMonthInr()).isEqualByComparingTo("1000.00");
        assertThat(summary.getCollectedThisMonthInr()).isEqualByComparingTo("400.00");
        assertThat(summary.getOutstandingInr()).isEqualByComparingTo("600.00");
        assertThat(summary.getOverdueInr()).isEqualByComparingTo("600.00");
        assertThat(summary.getOutputTaxThisMonthInr()).isEqualByComparingTo("50.00");
    }

    @Test
    void gstSummaryGroupsByRate() {
        Invoice inv = invoice(new BigDecimal("1050.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null)
                .toBuilder().taxableValue(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(inv));
        InvoiceTax tax = InvoiceTax.builder().id("TX1").invoiceId("I1").label("GST 5%")
                .taxKind(TaxKind.GST).mode(TaxLineMode.PERCENT).ratePercent(new BigDecimal("5.000"))
                .cgstAmount(new BigDecimal("25.00")).sgstAmount(new BigDecimal("25.00")).igstAmount(BigDecimal.ZERO)
                .amount(new BigDecimal("50.00")).visibleToCustomer(true)
                .build();
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(tax));

        List<GstSummaryRowResponse> rows = accountsDashboardService.gstSummary(LocalDate.now().minusMonths(1), LocalDate.now());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getGstRatePercent()).isEqualByComparingTo("5");
        assertThat(rows.get(0).getTaxableValueInr()).isEqualByComparingTo("1000.00");
        assertThat(rows.get(0).getGstTotalInr()).isEqualByComparingTo("50.00");
    }

    @Test
    void tcsSummaryExcludesInvoicesWithNoTcs() {
        Invoice noTcs = invoice(new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, null);
        Invoice withTcs = noTcs.toBuilder().id("I2").tcsAmountInr(new BigDecimal("50.00"))
                .tcsSection("206C(1G)").tcsRatePercent(new BigDecimal("5.000"))
                .tcsBaseAmount(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(noTcs, withTcs));

        List<TcsSummaryRowResponse> rows = accountsDashboardService.tcsSummary(LocalDate.now().minusMonths(1), LocalDate.now());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTcsSection()).isEqualTo("206C(1G)");
        assertThat(rows.get(0).getTcsAmountInr()).isEqualByComparingTo("50.00");
    }
}
