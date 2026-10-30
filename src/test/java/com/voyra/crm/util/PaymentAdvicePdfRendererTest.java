package com.voyra.crm.util;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.PaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The supplier payment advice: a deposit receipt with no bill, a settlement summary against one. */
class PaymentAdvicePdfRendererTest {

    private static SupplierPayment payment() {
        return SupplierPayment.builder().id("P1").voucherNumber("PV-0001").paidOn(LocalDate.of(2026, 9, 19))
                .paymentMode(PaymentMode.values()[0]).currencyCode("INR").amount(new BigDecimal("-1500.00")).build();
    }

    private static SupplierInvoice bill() {
        return SupplierInvoice.builder().supplierInvoiceNumber("SB-77").grandTotal(new BigDecimal("5000.00"))
                .balanceDue(new BigDecimal("2500.00")).build();
    }

    private static SupplierPayment prepayment(String id, String amount) {
        return SupplierPayment.builder().id(id).appliedFromAdvance(true).amount(new BigDecimal(amount)).build();
    }

    @Test
    void anAdvanceWithoutABillReadsAsADepositReceipt() {
        String t = PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, null, null));

        assertThat(t).contains("PAYMENT ADVICE", "Paid To", "-", "Voucher: PV-0001", "Date: 2026-09-19",
                "Mode: " + PaymentMode.values()[0], "Currency: INR", "Advance / Deposit1500.00",
                "Net Settled Amount1500.00", "One Thousand Five Hundred Rupees Only");
        assertThat(t).doesNotContain("Bill Reference", "Paid From", "Notes:", "A/C:", "(@");
    }

    @Test
    void aMissingVoucherDateAndModePrintDashes() {
        SupplierPayment bare = payment().toBuilder().voucherNumber(" ").paidOn(null).paymentMode(null).build();

        String t = PdfText.of(PaymentAdvicePdfRenderer.write(bare, null, null, null, null));

        assertThat(t).contains("Voucher: -", "Date: -", "Mode: -");
    }

    @Test
    void aBillShowsGrossOnlyLivePrepaymentsFromOtherVouchersNetAndBalance() {
        List<SupplierPayment> onBill = List.of(
                prepayment("A1", "1000.00"),
                prepayment("A2", "200.00").toBuilder().appliedFromAdvance(false).build(),
                prepayment("A3", "300.00").toBuilder().appliedFromAdvance(null).build(),
                prepayment("A4", "400.00").toBuilder().reversedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build(),
                prepayment("A5", "500.00").toBuilder().reversesPaymentId("A1").build(),
                prepayment("P1", "1500.00"));

        String t = PdfText.of(PaymentAdvicePdfRenderer.write(payment(), bill(), onBill, null, null));

        assertThat(t).contains("Bill ReferenceSB-77", "Gross Bill Amount5000.00", "Prepayments Deducted(1000.00)",
                "Net Settled Amount (this voucher)1500.00", "Balance Due After This Payment2500.00");
        assertThat(t).doesNotContain("Advance / Deposit");
    }

    @Test
    void noPrepaymentsMeansNoDeductionRow() {
        String withNull = PdfText.of(PaymentAdvicePdfRenderer.write(payment(), bill().toBuilder().supplierInvoiceNumber(null).build(),
                null, null, null));
        String onlyThisVoucher = PdfText.of(PaymentAdvicePdfRenderer.write(payment(), bill(),
                List.of(prepayment("P1", "1500.00")), null, null));

        assertThat(withNull).contains("Bill Reference-").doesNotContain("Prepayments Deducted");
        assertThat(onlyThisVoucher).doesNotContain("Prepayments Deducted");
    }

    @Test
    void theVendorsBankAccountPrintsWithItsIfscWhenKnown() {
        Vendor vendor = Vendor.builder().name("Vendor One").bankAccountNumber("123456").bankIfsc("TEST0000002").build();
        Vendor noIfsc = vendor.toBuilder().bankIfsc(" ").build();
        Vendor noAccount = vendor.toBuilder().bankAccountNumber("").build();

        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, vendor, null)))
                .contains("Vendor One", "A/C: 123456  IFSC: TEST0000002");
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, noIfsc, null)))
                .contains("A/C: 123456").doesNotContain("IFSC");
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, noAccount, null)))
                .contains("Vendor One").doesNotContain("A/C:");
    }

    @Test
    void theAgencysBankAccountPrintsAsPaidFromWithOnlyTheFieldsItHas() {
        Tenant full = new Tenant();
        full.setBankAccountName("Agency Account");
        full.setBankAccountNumber("999888");
        full.setBankIfscCode("TEST0000003");
        full.setBankBranch("Main Branch");
        Tenant minimal = new Tenant();
        minimal.setBankAccountNumber("999888");
        minimal.setBankAccountName(" ");
        Tenant none = new Tenant();
        none.setBankAccountNumber(" ");

        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, null, full)))
                .contains("Paid From", "Agency Account", "Account Number: 999888", "IFSC: TEST0000003", "Branch: Main Branch");
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, null, minimal)))
                .contains("Paid From", "Account Number: 999888").doesNotContain("IFSC:", "Branch:");
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), null, null, null, none)))
                .doesNotContain("Paid From");
    }

    @Test
    void aForeignCurrencyPaymentShowsItsRate() {
        SupplierPayment usd = payment().toBuilder().currencyCode("USD").fxRateToInr(new BigDecimal("83.25")).build();

        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(usd, null, null, null, null)))
                .contains("Currency: USD (@ 83.25)");
    }

    @Test
    void notesPrintOnlyWhenNotBlank() {
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment().toBuilder().notes("Paid early").build(), null, null, null, null)))
                .contains("Notes: Paid early");
        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment().toBuilder().notes(" ").build(), null, null, null, null)))
                .doesNotContain("Notes:");
    }

    @Test
    void aBillWithoutFiguresPrintsZeros() {
        SupplierInvoice empty = bill().toBuilder().grandTotal(null).balanceDue(null).build();

        assertThat(PdfText.of(PaymentAdvicePdfRenderer.write(payment(), empty, List.of(), null, null)))
                .contains("Gross Bill Amount0.00", "Balance Due After This Payment0.00");
    }
}
