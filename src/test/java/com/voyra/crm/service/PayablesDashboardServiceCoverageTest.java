package com.voyra.crm.service;

import com.voyra.crm.dto.CostReconciliationRowResponse;
import com.voyra.crm.dto.PayablesDashboardSummaryResponse;
import com.voyra.crm.dto.PurchaseRegisterRowResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.SupplierInvoiceLineItemRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Payables summary, purchase register and cost reconciliation figures, to the paisa. */
@ExtendWith(MockitoExtension.class)
class PayablesDashboardServiceCoverageTest {

    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierInvoiceLineItemRepository lineItemRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private BookingRepository bookingRepository;
    @InjectMocks private PayablesDashboardService service;

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    private static SupplierInvoice unpaid(SupplierInvoiceStatus status, String balance, LocalDate due) {
        return SupplierInvoice.builder().status(status).balanceDueInr(new BigDecimal(balance)).dueDate(due).build();
    }

    private static SupplierPayment pay(Boolean advance, Boolean applied, String inr, LocalDateTime reversedAt, String reverses) {
        return SupplierPayment.builder().direction(SupplierPaymentDirection.PAYMENT).isAdvance(advance)
                .appliedFromAdvance(applied).amountInr(new BigDecimal(inr)).reversedAt(reversedAt).reversesPaymentId(reverses).build();
    }

    @Test
    void summaryCountsOnlyOpenApprovedBillsOverdueByDueDateAndLiveAdvancesLessApplied() {
        LocalDate today = LocalDate.now();
        when(supplierInvoiceRepository.findByStatusNotInAndInvoiceDateBetween(any(), eq(today.withDayOfMonth(1)),
                eq(today.withDayOfMonth(today.lengthOfMonth())))).thenReturn(List.of(
                SupplierInvoice.builder().grandTotalInr(new BigDecimal("1000.00")).gstTotalInr(null).build()));
        when(supplierPaymentRepository.findByDirectionAndPaidOnBetween(eq(SupplierPaymentDirection.PAYMENT), any(), any()))
                .thenReturn(List.of(pay(true, false, "9999", null, null), pay(false, false, "250.00", null, null)));
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of(
                unpaid(SupplierInvoiceStatus.APPROVED, "300.00", today.minusDays(1)),
                unpaid(SupplierInvoiceStatus.PARTIALLY_PAID, "200.00", today),
                unpaid(SupplierInvoiceStatus.APPROVED, "50.00", null),
                unpaid(SupplierInvoiceStatus.APPROVED, "0.00", today.minusDays(9)),
                unpaid(SupplierInvoiceStatus.PAID, "0.00", null),
                unpaid(SupplierInvoiceStatus.DRAFT, "999.00", today.minusDays(9))));
        when(supplierPaymentRepository.findAll()).thenReturn(List.of(
                pay(true, false, "1000.00", null, null),
                pay(true, false, "400.00", LocalDateTime.now(), null),
                pay(true, false, "-400.00", null, "X"),
                pay(null, null, "77.00", null, null),
                pay(false, true, "300.00", null, null),
                pay(false, true, "100.00", LocalDateTime.now(), null),
                pay(false, true, "-100.00", null, "Y")));
        when(supplierInvoiceRepository.countByStatus(SupplierInvoiceStatus.DRAFT)).thenReturn(4L);

        PayablesDashboardSummaryResponse r = service.summary();

        assertThat(r.getBilledThisMonthInr()).isEqualByComparingTo("1000.00");
        assertThat(r.getInputTaxThisMonthInr()).isEqualByComparingTo("0");
        assertThat(r.getPaidThisMonthInr()).isEqualByComparingTo("250.00");
        assertThat(r.getPayableOutstandingInr()).isEqualByComparingTo("550.00");
        assertThat(r.getOverdueInr()).isEqualByComparingTo("300.00");
        assertThat(r.getAdvancesHeldInr()).isEqualByComparingTo("700.00");
        assertThat(r.getBillsAwaitingApprovalCount()).isEqualTo(4L);
    }

    @Test
    void advancesHeldNeverGoNegative() {
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of());
        when(supplierPaymentRepository.findAll()).thenReturn(List.of(pay(false, true, "300.00", null, null)));

        assertThat(service.summary().getAdvancesHeldInr()).isEqualByComparingTo("0");
    }

    private static SupplierInvoiceLineItem line(String sac, String rate, String taxable, String cgst, String sgst, String igst) {
        return SupplierInvoiceLineItem.builder().sacCode(sac).gstRatePercent(new BigDecimal(rate))
                .taxableValue(new BigDecimal(taxable)).cgstAmount(new BigDecimal(cgst))
                .sgstAmount(new BigDecimal(sgst)).igstAmount(new BigDecimal(igst)).build();
    }

    private void registerData() {
        SupplierInvoice inr = SupplierInvoice.builder().id("SI1").fxRateToInr(BigDecimal.ONE).itcEligibility(ItcEligibility.ELIGIBLE).build();
        SupplierInvoice usd = SupplierInvoice.builder().id("SI2").fxRateToInr(new BigDecimal("83.333")).itcEligibility(ItcEligibility.ELIGIBLE).build();
        SupplierInvoice blocked = SupplierInvoice.builder().id("SI3").fxRateToInr(BigDecimal.ONE).itcEligibility(ItcEligibility.BLOCKED).build();
        when(supplierInvoiceRepository.findByStatusNotInAndInvoiceDateBetween(
                List.of(SupplierInvoiceStatus.CANCELLED, SupplierInvoiceStatus.DRAFT), FROM, TO)).thenReturn(List.of(inr, usd, blocked));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(
                line("998552", "18.00", "1000.00", "90.00", "90.00", "0"),
                line(null, "5", "200.00", "0", "0", "10.00")));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI2")).thenReturn(List.of(
                line("998552", "18", "10.00", "0.90", "0.90", "0")));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI3")).thenReturn(List.of(
                line("998552", "18", "500.00", "45.00", "45.00", "0")));
    }

    @Test
    void purchaseRegisterGroupsBySacRateAndItcInBase() {
        registerData();

        List<PurchaseRegisterRowResponse> rows = service.purchaseRegister(FROM, TO);

        assertThat(rows).hasSize(3);
        PurchaseRegisterRowResponse eligible18 = rows.get(0);
        assertThat(eligible18.getSacCode()).isEqualTo("998552");
        assertThat(eligible18.getGstRatePercent()).isEqualByComparingTo("18");
        assertThat(eligible18.getItcEligibility()).isEqualTo(ItcEligibility.ELIGIBLE);
        // 1000 + 10 x 83.333 = 833.33
        assertThat(eligible18.getTaxableValueInr()).isEqualByComparingTo("1833.33");
        assertThat(eligible18.getCgstAmountInr()).isEqualByComparingTo("165.00");
        assertThat(eligible18.getSgstAmountInr()).isEqualByComparingTo("165.00");
        assertThat(eligible18.getGstTotalInr()).isEqualByComparingTo("330.00");
        PurchaseRegisterRowResponse noSac = rows.get(1);
        assertThat(noSac.getSacCode()).isEqualTo("-");
        assertThat(noSac.getIgstAmountInr()).isEqualByComparingTo("10.00");
        assertThat(noSac.getGstTotalInr()).isEqualByComparingTo("10.00");
        assertThat(rows.get(2).getItcEligibility()).isEqualTo(ItcEligibility.BLOCKED);
        assertThat(rows.get(2).getTaxableValueInr()).isEqualByComparingTo("500.00");
    }

    @Test
    void purchaseRegisterExportsOneRowPerGroup() throws Exception {
        registerData();

        byte[] xlsx = service.exportPurchaseRegisterXlsx(FROM, TO);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("SAC Code");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("998552");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("18");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("ELIGIBLE");
            assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("330.00");
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
        }
    }

    private void reconciliationData() {
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of(
                SupplierInvoice.builder().status(SupplierInvoiceStatus.APPROVED).bookingId("B1").grandTotalInr(new BigDecimal("700.00")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.PAID).bookingId("B1").grandTotalInr(new BigDecimal("450.00")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.APPROVED).bookingId("B2").grandTotalInr(new BigDecimal("300.00")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.APPROVED).bookingId("GONE").grandTotalInr(new BigDecimal("1.00")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.CANCELLED).bookingId("B1").grandTotalInr(new BigDecimal("5000")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.DRAFT).bookingId("B1").grandTotalInr(new BigDecimal("5000")).build(),
                SupplierInvoice.builder().status(SupplierInvoiceStatus.APPROVED).bookingId(null).grandTotalInr(new BigDecimal("5000")).build()));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").clientName("Client One")
                .destination("Goa").netCost(new BigDecimal("1000.00")).build()));
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(Booking.builder().id("B2").netCost(null).build()));
        when(bookingRepository.findById("GONE")).thenReturn(Optional.empty());
    }

    @Test
    void costReconciliationComparesBillsWithTheEstimateAndSkipsUnknownBookings() {
        reconciliationData();

        List<CostReconciliationRowResponse> rows = service.costReconciliation();

        assertThat(rows).hasSize(2);
        CostReconciliationRowResponse b1 = rows.get(0);
        assertThat(b1.getActualCostInr()).isEqualByComparingTo("1150.00");
        assertThat(b1.getEstimatedCostInr()).isEqualByComparingTo("1000.00");
        assertThat(b1.getVarianceInr()).isEqualByComparingTo("150.00");
        assertThat(b1.getVariancePercent()).isEqualByComparingTo("15.00");
        CostReconciliationRowResponse b2 = rows.get(1);
        assertThat(b2.getEstimatedCostInr()).isEqualByComparingTo("0");
        assertThat(b2.getVarianceInr()).isEqualByComparingTo("300.00");
        assertThat(b2.getVariancePercent()).isNull();
    }

    @Test
    void costReconciliationExportBlanksMissingNamesAndPercent() throws Exception {
        reconciliationData();

        byte[] xlsx = service.exportCostReconciliationXlsx();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("Variance %");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Client One");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("15.00");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("300.00");
            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEmpty();
        }
    }
}
