package com.voyra.crm.util;

import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CreditNotePdfRendererTest {

    private CreditNote sampleNote() {
        return CreditNote.builder().id("CN1").creditNoteNumber("CN/2026-27/0001").financialYear("2026-27")
                .invoiceId("I1").invoiceNumber("INV/2026-27/0001")
                .clientId("K1").clientName("Rahul Verma").bookingId("B1")
                .reason(CreditNoteReason.BOOKING_CANCELLED).reasonNote("Client cancelled")
                .status(CreditNoteStatus.ISSUED).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("100000.00")).cgstAmount(new BigDecimal("9000.00"))
                .sgstAmount(new BigDecimal("9000.00")).igstAmount(BigDecimal.ZERO).tcsAmount(BigDecimal.ZERO)
                .cancellationFee(BigDecimal.ZERO).totalAmount(new BigDecimal("118000.00")).totalAmountInr(new BigDecimal("118000.00"))
                .refundableAmount(new BigDecimal("118000.00")).refundedAmount(BigDecimal.ZERO)
                .noteDate(LocalDate.of(2026, 9, 19)).build();
    }

    @Test
    void producesARealPdf() {
        byte[] pdf = CreditNotePdfRenderer.write(sampleNote());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void reRenderingTheSamePersistedCreditNoteIsContentIdentical() {
        CreditNote note = sampleNote();

        byte[] first = CreditNotePdfRenderer.write(note);
        byte[] second = CreditNotePdfRenderer.write(note);

        assertThat(InvoicePdfRendererTest.stripVolatileMetadata(second))
                .isEqualTo(InvoicePdfRendererTest.stripVolatileMetadata(first));
    }
}
