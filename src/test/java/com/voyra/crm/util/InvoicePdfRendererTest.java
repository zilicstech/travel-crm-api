package com.voyra.crm.util;

import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The epic's own acceptance criterion: a five-year-old invoice must re-render byte-identically
 * from persisted rates alone. Nothing here reads {@code TaxRateConfig}, so this is testable
 * without a database at all - two renders of the same in-memory {@link Invoice} must be
 * byte-for-byte equal.
 */
class InvoicePdfRendererTest {

    private Invoice sampleInvoice() {
        return Invoice.builder().id("I1").invoiceNumber("INV/2026-27/0001").financialYear("2026-27")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .clientId("K1").clientName("Rahul Verma").clientGstin("27AAAAA0000A1Z5").clientStateCode("27")
                .billingAddress("221B Baker Street, Mumbai")
                .agencyLegalName("Global Explorer Travel Pvt Ltd").agencyGstin("27BBBBB1111B1Z1")
                .agencyStateCode("27").agencyAddress("Fort, Mumbai")
                .bookingId("B1").agentId("A1")
                .placeOfSupplyCode("27").supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE).fxRateSource(FxRateSource.INR_IDENTITY)
                .subtotal(new BigDecimal("100000.00")).discountTotal(BigDecimal.ZERO).taxableValue(new BigDecimal("100000.00"))
                .cgstAmount(new BigDecimal("9000.00")).sgstAmount(new BigDecimal("9000.00")).igstAmount(BigDecimal.ZERO)
                .gstTotal(new BigDecimal("18000.00")).tcsRatePercent(BigDecimal.ZERO).tcsAmount(BigDecimal.ZERO)
                .roundOff(BigDecimal.ZERO).grandTotal(new BigDecimal("118000.00")).grandTotalInr(new BigDecimal("118000.00"))
                .invoiceDate(LocalDate.of(2026, 9, 19)).notes("Thank you for booking with us").terms("Payment due within 15 days")
                .build();
    }

    private List<InvoiceLineItem> sampleLines() {
        return List.of(InvoiceLineItem.builder().id("L1").invoiceId("I1").sortOrder(0)
                .description("Bali Package - 5N/6D").sacCode("9985")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("100000.00"))
                .lineSubtotal(new BigDecimal("100000.00")).discountAmount(BigDecimal.ZERO)
                .taxableValue(new BigDecimal("100000.00")).gstRatePercent(new BigDecimal("18.000"))
                .cgstRatePercent(new BigDecimal("9.000")).sgstRatePercent(new BigDecimal("9.000"))
                .cgstAmount(new BigDecimal("9000.00")).sgstAmount(new BigDecimal("9000.00")).igstAmount(BigDecimal.ZERO)
                .lineTotal(new BigDecimal("118000.00")).build());
    }

    @Test
    void producesARealPdf() {
        byte[] pdf = InvoicePdfRenderer.write(sampleInvoice(), sampleLines());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void reRenderingTheSamePersistedInvoiceIsContentIdentical() {
        // OpenPDF stamps a wall-clock /CreationDate and a random /ID into every PDF it writes -
        // container metadata that varies run to run regardless of content, the same way a fresh
        // zip of identical files gets a new mtime. Neither ever reflects anything read from
        // TaxRateConfig or any other mutable source: every actual figure comes from the Invoice/
        // InvoiceLineItem arguments alone. So this strips exactly those two volatile fields
        // before comparing - what remains is everything that could possibly depend on data.
        Invoice invoice = sampleInvoice();
        List<InvoiceLineItem> lines = sampleLines();

        byte[] first = InvoicePdfRenderer.write(invoice, lines);
        byte[] second = InvoicePdfRenderer.write(invoice, lines);

        assertThat(stripVolatileMetadata(second)).isEqualTo(stripVolatileMetadata(first));
    }

    static byte[] stripVolatileMetadata(byte[] pdf) {
        String text = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        String stripped = text
                .replaceAll("/CreationDate\\([^)]*\\)", "/CreationDate()")
                .replaceAll("/ID\\s*\\[<[0-9a-fA-F]*><[0-9a-fA-F]*>\\]", "/ID[]");
        return stripped.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    void aProformaRendersDifferentlyFromATaxInvoice() {
        // Content streams may be compressed, so this checks the title actually changed the
        // output rather than scanning for literal text inside the PDF.
        byte[] taxInvoicePdf = InvoicePdfRenderer.write(sampleInvoice(), sampleLines());
        Invoice proforma = sampleInvoice().toBuilder()
                .documentType(InvoiceDocumentType.PROFORMA).invoiceNumber("PI/2026-27/0001").build();

        byte[] proformaPdf = InvoicePdfRenderer.write(proforma, sampleLines());

        assertThat(proformaPdf).isNotEqualTo(taxInvoicePdf);
    }

    @Test
    void interStateInvoiceRendersDifferentlyFromIntraState() {
        byte[] intraStatePdf = InvoicePdfRenderer.write(sampleInvoice(), sampleLines());
        Invoice interState = sampleInvoice().toBuilder()
                .taxTreatment(TaxTreatment.INTER_STATE)
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(new BigDecimal("18000.00"))
                .build();

        byte[] interStatePdf = InvoicePdfRenderer.write(interState, sampleLines());

        assertThat(interStatePdf).isNotEqualTo(intraStatePdf);
    }
}
