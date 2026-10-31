package com.voyra.crm.util;

import com.voyra.crm.entity.SubscriptionInvoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Voyra's subscription tax invoice to an agency, read back from the rendered PDF. */
class SubscriptionInvoicePdfRendererTest {

    private static SubscriptionInvoice intraState() {
        Map<String, Object> seller = Map.of("legalName", "Voyra Seller Pvt Ltd", "address", "Seller Road",
                "gstin", "27AAAAA0000A1Z5", "stateCode", "27");
        Map<String, Object> buyer = Map.of("name", "Agency Buyer", "address", "Buyer Lane", "gstin", "27BBBBB1111B1Z1",
                "stateCode", "27");
        Map<String, Object> line = new HashMap<>();
        line.put("description", "CRM Core");
        line.put("quantity", 1);
        line.put("unitPrice", new BigDecimal("2499"));
        line.put("months", 1);
        line.put("amount", "2499");
        return SubscriptionInvoice.builder().tenantId("tenant-1").number("VOY-SUB-2026-1")
                .createdAt(Instant.parse("2026-10-01T00:00:00Z"))
                .periodStart(Instant.parse("2026-10-01T00:00:00Z")).periodEnd(Instant.parse("2026-10-31T00:00:00Z"))
                .dueAt(Instant.parse("2026-10-08T00:00:00Z")).sacCode("998314")
                .billingDetails(new HashMap<>(Map.of("seller", seller, "buyer", buyer)))
                .lines(List.of(line))
                .amount(new BigDecimal("2499.00")).cgst(new BigDecimal("224.91")).sgst(new BigDecimal("224.91"))
                .igst(BigDecimal.ZERO).gst(new BigDecimal("449.82")).total(new BigDecimal("2948.82"))
                .status("ISSUED").build();
    }

    @Test
    void anIntraStateInvoicePrintsBothPartiesTheLinesAndCgstPlusSgst() {
        String t = PdfText.flat(SubscriptionInvoicePdfRenderer.write(intraState()));

        assertThat(t).contains(PdfText.flat("TAX INVOICE", "Supplier", "Voyra Seller Pvt Ltd", "Seller Road", "GSTIN: 27AAAAA0000A1Z5",
                "Billed To", "Agency Buyer", "Buyer Lane", "GSTIN: 27BBBBB1111B1Z1", "State code: 27",
                "Invoice No.VOY-SUB-2026-1", "Invoice Date01 Oct 2026", "Period01 Oct 2026 - 31 Oct 2026",
                "Due Date08 Oct 2026", "Place of Supply27", "SAC998314",
                "CRM Core", "2499.00", "CGST @ 9%224.91", "SGST @ 9%224.91", "Total (INR)2948.82",
                "Two Thousand Nine Hundred Forty Eight Rupees & Eighty Two Paise Only"));
        assertThat(t).doesNotContain(PdfText.flat("IGST", "Status"));
    }

    @Test
    void anInterStateInvoicePrintsIgst() {
        SubscriptionInvoice inv = intraState().toBuilder().cgst(BigDecimal.ZERO).sgst(BigDecimal.ZERO)
                .igst(new BigDecimal("449.82")).build();

        assertThat(PdfText.flat(SubscriptionInvoicePdfRenderer.write(inv)))
                .contains(PdfText.flat("IGST @ 18%449.82")).doesNotContain(PdfText.flat("CGST"));
    }

    @Test
    void withoutAnyTaxSplitIgstFallsBackToTheGstTotal() {
        SubscriptionInvoice nullSplit = intraState().toBuilder().cgst(null).igst(null).gst(new BigDecimal("12.34")).build();
        SubscriptionInvoice zeroSplit = intraState().toBuilder().cgst(BigDecimal.ZERO).igst(BigDecimal.ZERO)
                .gst(new BigDecimal("12.34")).build();

        assertThat(PdfText.flat(SubscriptionInvoicePdfRenderer.write(nullSplit))).contains(PdfText.flat("IGST @ 18%12.34"));
        assertThat(PdfText.flat(SubscriptionInvoicePdfRenderer.write(zeroSplit))).contains(PdfText.flat("IGST @ 18%12.34"));
    }

    @Test
    void aPaidInvoiceShowsWhenAndAVoidOneSaysVoid() {
        SubscriptionInvoice paid = intraState().toBuilder().status("PAID").paidAt(Instant.parse("2026-10-05T10:00:00Z")).build();
        SubscriptionInvoice voided = intraState().toBuilder().status("VOID").build();

        assertThat(PdfText.flat(SubscriptionInvoicePdfRenderer.write(paid))).contains(PdfText.flat("StatusPaid on 05 Oct 2026"));
        assertThat(PdfText.flat(SubscriptionInvoicePdfRenderer.write(voided))).contains(PdfText.flat("StatusVoid"));
    }

    @Test
    void missingPartiesDatesAndLineFiguresFallBack() {
        Map<String, Object> line = new HashMap<>();
        line.put("description", "Seats");
        SubscriptionInvoice inv = intraState().toBuilder().billingDetails(null).number(null).createdAt(null)
                .periodStart(null).periodEnd(null).dueAt(null).sacCode(" ").lines(List.of(line)).build();

        String t = PdfText.flat(SubscriptionInvoicePdfRenderer.write(inv));

        assertThat(t).contains(PdfText.flat("Voyra", "tenant-1", "GSTIN: -", "State code: -", "Invoice No.-", "Invoice Date-",
                "Period- - -", "Due Date-", "Place of Supply-", "SAC-", "Seats", "null", "0.00"));
        assertThat(t).doesNotContain(PdfText.flat("Seller Road", "Buyer Lane"));
    }

    @Test
    void aPartyThatIsNotAMapOrHasBlankNamesFallsBack() {
        Map<String, Object> details = new HashMap<>();
        details.put("seller", "not a map");
        details.put("buyer", Map.of("name", " "));
        SubscriptionInvoice inv = intraState().toBuilder().billingDetails(details).build();

        String t = PdfText.flat(SubscriptionInvoicePdfRenderer.write(inv));

        assertThat(t).contains(PdfText.flat("Voyra", "tenant-1")).doesNotContain(PdfText.flat("Voyra Seller Pvt Ltd", "Agency Buyer"));
    }
}
