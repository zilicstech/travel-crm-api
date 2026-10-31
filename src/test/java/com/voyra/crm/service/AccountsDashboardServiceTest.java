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
    @Mock
    private com.voyra.crm.repository.JournalLineRepository journalLineRepository;

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
        // Advances held is the Client Advances Held (2110) balance: 2,500 credited less 700 used.
        when(journalLineRepository.sumCreditBefore(eq("2110"), any())).thenReturn(new BigDecimal("2500.00"));
        when(journalLineRepository.sumDebitBefore(eq("2110"), any())).thenReturn(new BigDecimal("700.00"));

        AccountsDashboardSummaryResponse summary = accountsDashboardService.summary();
        assertThat(summary.getAdvanceHeldInr()).isEqualByComparingTo("1800.00");

        assertThat(summary.getBilledThisMonthInr()).isEqualByComparingTo("1000.00");
        assertThat(summary.getCollectedThisMonthInr()).isEqualByComparingTo("400.00");
        assertThat(summary.getOutstandingInr()).isEqualByComparingTo("600.00");
        assertThat(summary.getOverdueInr()).isEqualByComparingTo("600.00");
        assertThat(summary.getOutputTaxThisMonthInr()).isEqualByComparingTo("50.00");
    }

    @Mock
    private com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;
    @Mock
    private com.voyra.crm.repository.TaxRateConfigRepository taxRateConfigRepository;

    /**
     * BUG-CRX-21: a commission-agent invoice stores the full package (9,000) as its taxable value, but
     * GST was levied on the 1,000 fee only - the register reads that fee from the invoice's own posted
     * journal (the 4600 credit), and takes the SAC from the tax slab the row was chosen from.
     */
    @Test
    void gstSummaryTaxesTheFeeOnACommissionAgentInvoiceAndShowsTheSlabSac() {
        Invoice flight = invoice(new BigDecimal("9180.00"), new BigDecimal("180.00"), BigDecimal.ZERO, null)
                .toBuilder().billingModel(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT)
                .taxableValue(new BigDecimal("9000.00")).taxableValueInr(new BigDecimal("9000.00")).build();
        Invoice hotel = invoice(new BigDecimal("14160.00"), new BigDecimal("2160.00"), BigDecimal.ZERO, null)
                .toBuilder().id("I2").billingModel(com.voyra.crm.enums.InvoiceBillingModel.PRINCIPAL)
                .taxableValue(new BigDecimal("12000.00")).taxableValueInr(new BigDecimal("12000.00")).build();
        Invoice draft = hotel.toBuilder().id("I3").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(flight, hotel, draft));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(gst18("TX1", "I1", "90.00")));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I2")).thenReturn(List.of(gst18("TX2", "I2", "1080.00")));
        when(taxRateConfigRepository.findAllById(any())).thenReturn(List.of(
                com.voyra.crm.entity.TaxRateConfig.builder().id("CFG18").sacCode("9985").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(com.voyra.crm.enums.JournalSourceType.INVOICE, "I1",
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(java.util.Optional.of(com.voyra.crm.entity.JournalEntry.builder().id("JE1").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(List.of(
                com.voyra.crm.entity.JournalLine.builder().accountCode("1200").debitAmountInr(new BigDecimal("9180.00")).creditAmountInr(BigDecimal.ZERO).build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("2210").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("8000.00")).build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("4600").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("1000.00")).build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("2310").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("180.00")).build()));

        List<GstSummaryRowResponse> rows = accountsDashboardService.gstSummary(LocalDate.now().minusMonths(1), LocalDate.now());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSacCode()).isEqualTo("9985");
        assertThat(rows.get(0).getGstRatePercent()).isEqualByComparingTo("18");
        assertThat(rows.get(0).getTaxableValueInr()).isEqualByComparingTo("13000.00");
        assertThat(rows.get(0).getGstTotalInr()).isEqualByComparingTo("2340.00");
    }

    private static InvoiceTax gst18(String id, String invoiceId, String half) {
        return InvoiceTax.builder().id(id).invoiceId(invoiceId).label("GST 18%").taxRateConfigId("CFG18")
                .taxKind(TaxKind.GST).mode(TaxLineMode.PERCENT).ratePercent(new BigDecimal("18.000"))
                .cgstAmount(new BigDecimal(half)).sgstAmount(new BigDecimal(half)).igstAmount(BigDecimal.ZERO)
                .amount(new BigDecimal(half).multiply(new BigDecimal("2"))).visibleToCustomer(true)
                .build();
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

    // ---- remaining branches

    @Test
    void walletAdjustmentsAreNotCollectedAndOnlyPastDueDatesAreOverdue() {
        LocalDate today = LocalDate.now();
        when(paymentReceiptRepository.findByDirectionAndReceivedOnBetween(eq(ReceiptDirection.RECEIPT), any(), any())).thenReturn(List.of(
                PaymentReceipt.builder().paymentMode(com.voyra.crm.enums.PaymentMode.ADJUSTMENT).amountInr(new BigDecimal("900.00")).build(),
                PaymentReceipt.builder().paymentMode(com.voyra.crm.enums.PaymentMode.CASH).amountInr(new BigDecimal("100.00")).build()));
        when(invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(any(), any(), any())).thenReturn(List.of(
                invoice(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("10.00"), null),
                invoice(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("20.00"), today),
                invoice(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("40.00"), today.minusDays(1))));
        when(journalLineRepository.sumCreditBefore(eq("2110"), any())).thenReturn(BigDecimal.ZERO);
        when(journalLineRepository.sumDebitBefore(eq("2110"), any())).thenReturn(new BigDecimal("5.00"));
        when(creditNoteRepository.findByStatusAndNoteDateBetween(eq(com.voyra.crm.enums.CreditNoteStatus.ISSUED), any(), any()))
                .thenReturn(List.of(com.voyra.crm.entity.CreditNote.builder().taxableValue(new BigDecimal("10.005"))
                        .fxRateToInr(BigDecimal.ONE).build()));

        AccountsDashboardSummaryResponse s = accountsDashboardService.summary();

        assertThat(s.getCollectedThisMonthInr()).isEqualByComparingTo("100.00");
        assertThat(s.getOutstandingInr()).isEqualByComparingTo("70.00");
        assertThat(s.getOverdueInr()).isEqualByComparingTo("40.00");
        assertThat(s.getAdvanceHeldInr()).isEqualByComparingTo("0");
        assertThat(s.getNetRevenueThisMonthInr()).isEqualByComparingTo("-10.01");
    }

    @Test
    void gstSummarySkipsTcsRowsGroupsCustomTaxesUnderADashAndExports() throws Exception {
        Invoice usd = invoice(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, null).toBuilder()
                .fxRateToInr(new BigDecimal("80")).taxableValueInr(new BigDecimal("8000.00")).build();
        Invoice agent = invoice(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null).toBuilder().id("I2")
                .billingModel(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT).taxableValueInr(new BigDecimal("500.00")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any())).thenReturn(List.of(usd, agent));
        InvoiceTax tcs = InvoiceTax.builder().id("T0").invoiceId("I1").taxKind(TaxKind.TCS).build();
        InvoiceTax custom = InvoiceTax.builder().id("T1").invoiceId("I1").taxKind(TaxKind.GST).ratePercent(null)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(new BigDecimal("1.25")).build();
        InvoiceTax noSac = gst18("T2", "I1", "4.50").toBuilder().taxRateConfigId("CFG-NOSAC").build();
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(tcs, custom, noSac));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I2")).thenReturn(List.of(gst18("T3", "I2", "45.00")));
        when(taxRateConfigRepository.findAllById(any())).thenReturn(List.of(
                com.voyra.crm.entity.TaxRateConfig.builder().id("CFG-NOSAC").sacCode(null).build(),
                com.voyra.crm.entity.TaxRateConfig.builder().id("CFG18").sacCode("998555").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(com.voyra.crm.enums.JournalSourceType.INVOICE, "I2",
                com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(java.util.Optional.of(com.voyra.crm.entity.JournalEntry.builder().id("JE2").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE2")).thenReturn(List.of(
                com.voyra.crm.entity.JournalLine.builder().accountCode("4600").creditAmountInr(null).build(),
                com.voyra.crm.entity.JournalLine.builder().accountCode("4600").creditAmountInr(new BigDecimal("250.00")).build()));

        LocalDate from = LocalDate.now().minusMonths(1);
        List<GstSummaryRowResponse> rows = accountsDashboardService.gstSummary(from, LocalDate.now());

        assertThat(rows).extracting(GstSummaryRowResponse::getSacCode).containsExactly("-", "-", "998555");
        assertThat(rows.get(0).getGstRatePercent()).isEqualByComparingTo("0");
        assertThat(rows.get(0).getIgstAmountInr()).isEqualByComparingTo("100.00");
        assertThat(rows.get(1).getGstRatePercent()).isEqualByComparingTo("18");
        assertThat(rows.get(1).getCgstAmountInr()).isEqualByComparingTo("360.00");
        assertThat(rows.get(1).getTaxableValueInr()).isEqualByComparingTo("8000.00");
        assertThat(rows.get(2).getTaxableValueInr()).isEqualByComparingTo("250.00");

        byte[] xlsx = accountsDashboardService.exportGstXlsx(from, LocalDate.now());
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("SAC Code");
            assertThat(sh.getRow(3).getCell(0).getStringCellValue()).isEqualTo("998555");
            assertThat(sh.getRow(3).getCell(6).getStringCellValue()).isEqualTo("90.00");
        }
    }

    @Test
    void aCommissionAgentInvoiceWithoutItsJournalUsesTheTaxableValueAndTcsWithoutASectionIsADash() {
        Invoice agent = invoice(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null).toBuilder()
                .billingModel(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT).taxableValueInr(new BigDecimal("700.00"))
                .tcsAmountInr(new BigDecimal("5.00")).tcsSection(null).tcsBaseAmount(new BigDecimal("100")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusNotAndInvoiceDateBetween(any(), any(), any(), any())).thenReturn(List.of(agent));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(gst18("T1", "I1", "63.00")));
        when(taxRateConfigRepository.findAllById(any())).thenReturn(List.of());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(any(), any(), any())).thenReturn(java.util.Optional.empty());

        LocalDate from = LocalDate.now().minusMonths(1);
        assertThat(accountsDashboardService.gstSummary(from, LocalDate.now()).get(0).getTaxableValueInr()).isEqualByComparingTo("700.00");
        assertThat(accountsDashboardService.tcsSummary(from, LocalDate.now())).singleElement()
                .satisfies(r -> assertThat(r.getTcsSection()).isEqualTo("-"));
    }
}
