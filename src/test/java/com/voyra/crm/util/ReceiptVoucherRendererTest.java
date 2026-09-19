package com.voyra.crm.util;

import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptVoucherRendererTest {

    private PaymentReceipt sampleReceipt() {
        return PaymentReceipt.builder().id("R1").receiptNumber("RCP/2026-27/0001").financialYear("2026-27")
                .direction(ReceiptDirection.RECEIPT).invoiceId("I1").clientId("K1").bookingId("B1")
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("50000.00")).amountInr(new BigDecimal("50000.00"))
                .paymentMode(PaymentMode.BANK_TRANSFER).instrumentRef("UTR123").receivedOn(LocalDate.of(2026, 9, 19))
                .isAdvance(false).build();
    }

    @Test
    void producesARealPdf() {
        byte[] pdf = ReceiptVoucherRenderer.write(sampleReceipt(), "INV/2026-27/0001");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void reRenderingTheSamePersistedReceiptIsContentIdentical() {
        PaymentReceipt receipt = sampleReceipt();

        byte[] first = ReceiptVoucherRenderer.write(receipt, "INV/2026-27/0001");
        byte[] second = ReceiptVoucherRenderer.write(receipt, "INV/2026-27/0001");

        assertThat(InvoicePdfRendererTest.stripVolatileMetadata(second))
                .isEqualTo(InvoicePdfRendererTest.stripVolatileMetadata(first));
    }

    @Test
    void aRefundRendersAsARefundVoucherNotAReceipt() {
        PaymentReceipt refund = sampleReceipt().toBuilder().direction(ReceiptDirection.REFUND)
                .amount(new BigDecimal("-50000.00")).amountInr(new BigDecimal("-50000.00"))
                .reversesReceiptId("R0").build();

        byte[] receiptPdf = ReceiptVoucherRenderer.write(sampleReceipt(), "INV/2026-27/0001");
        byte[] refundPdf = ReceiptVoucherRenderer.write(refund, "INV/2026-27/0001");

        assertThat(refundPdf).isNotEqualTo(receiptPdf);
    }
}
