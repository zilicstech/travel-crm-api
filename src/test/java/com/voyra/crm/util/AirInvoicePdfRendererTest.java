package com.voyra.crm.util;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.PaxType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The air ticket invoice, as the agency's old software printed it - see Demo Invoice.pdf. */
class AirInvoicePdfRendererTest {

    private static Invoice invoice() {
        return Invoice.builder().id("9f1c2a7e-0000-0000-0000-000000000000").invoiceNumber("ITI00000001")
                .status(InvoiceLifecycle.ISSUED).serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL)
                .clientName("Shubh Camroon Staff").billingAddress("Bhopal").clientStateCode("23")
                .agencyLegalName("Skytourwayz Adventure LLP").agencyGstin("23AFFFS1126P1ZV").agencyStateCode("23")
                .agencyAddress("Bhopal (462011).").placeOfSupplyCode("23").currencyCode("INR")
                .taxableValue(new BigDecimal("140996.00")).roundOff(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("141356.00")).grandTotalInr(new BigDecimal("141356.00"))
                .invoiceDate(LocalDate.of(2026, 9, 19)).terms("# Subject to Bhopal jurisdiction.").build();
    }

    /** A 138,996 AED ticket (at 26.5) for two travellers on two connecting flights, sold with a 2,000 markup. */
    private static InvoiceLineItem ticket() {
        return InvoiceLineItem.builder().id("L1").bookingDocumentId("V1").description("DLA to BOM")
                .taxableValue(new BigDecimal("140996.00"))
                .charges(Map.of("basic", new BigDecimal("130000.00"), "yq", new BigDecimal("8996.00")))
                .travellers(List.of(new VoucherTraveller("M1", "Abhishek Kumar Singh", PaxType.ADULT),
                        new VoucherTraveller("M2", "Sheetal Singh", PaxType.ADULT)))
                .legs(List.of(
                        VoucherLeg.builder().from("DLA").to("ADD").airline("Ethiopian Airlines").flightNumber("ET 962")
                                .cabin("Economy").travelDate(LocalDate.of(2026, 10, 31)).build(),
                        VoucherLeg.builder().from("ADD").to("BOM").airline("Ethiopian Airlines").flightNumber("ET 640")
                                .cabin("Economy").travelDate(LocalDate.of(2026, 10, 31)).build()))
                .purchaseCurrency("AED").purchaseRate(new BigDecimal("26.5"))
                .build();
    }

    private static List<InvoiceTax> gstOnMarkup() {
        return List.of(InvoiceTax.builder().label("GST 18%").ratePercent(new BigDecimal("18"))
                .cgstAmount(new BigDecimal("180.00")).sgstAmount(new BigDecimal("180.00")).igstAmount(BigDecimal.ZERO)
                .amount(new BigDecimal("360.00")).visibleToCustomer(true).build());
    }

    private static Tenant agency() {
        Tenant t = new Tenant();
        t.setAgencyName("Skytourwayz");
        t.setBaseCurrencyCode("INR");
        t.setContactPhone("9993341873");
        t.setContactEmail("info@tourwayz.com");
        t.setWebsite("www.tourwayz.com");
        t.setBankAccountNumber("005505015417");
        return t;
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder sb = new StringBuilder();
        PdfTextExtractor ex = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) sb.append(ex.getTextFromPage(p)).append('\n');
        return sb.toString();
    }

    @Test
    void printsEveryPassengerFlightAndTheGstOnTheMarkup() throws Exception {
        String t = text(AirInvoicePdfRenderer.write(invoice(), List.of(ticket()), gstOnMarkup(), agency(),
                Map.of("V1", "LVBJAY"), null, "Sandli"));

        assertThat(t).contains("SKYTOURWAYZ ADVENTURE LLP", "(PAN) AFFFS1126P", "(GSTIN) 23AFFFS1126P1ZV", "(M) 9993341873",
                "(e) info@tourwayz.com", "International Air Ticket Invoice", "ITI00000001", "19 Sep 2026",
                "Airline PNR (LVBJAY)", "Place of Supply: Madhya Pradesh (23)", "Airline: Ethiopian Airlines");
        assertThat(t).contains("001 ABHISHEK KUMAR SINGH", "002 SHEETAL SINGH", "DLA  ADD  ECO", "ADD  BOM  ECO",
                "ET 962", "ET 640", "31/10/26", "LVBJAY");
        // Basic + YQ + Other (the markup) = the row's Total = the Gross Total.
        assertThat(t).contains("130000", "8996", "2000", "140996.00", "Gross Total");
        assertThat(t).contains("CGST 9%", "SGST 9%", "180.00", "Total Amount in INR", "141356.00");
        // Billed in base: no other currency, the words in rupees - whatever the ticket was bought in.
        assertThat(t).doesNotContain("Total Amount in AED").contains("Rupees");
        assertThat(t).contains("(Prepared by: Sandli)", "E. & O. E.", "for SKYTOURWAYZ ADVENTURE LLP");
    }

    @Test
    void anInterStateInvoicePrintsIgstAndARupeeTicketNoForeignTotal() throws Exception {
        InvoiceLineItem rupees = ticket();
        rupees.setPurchaseCurrency("INR");
        rupees.setPurchaseRate(BigDecimal.ONE);
        List<InvoiceTax> igst = List.of(InvoiceTax.builder().label("GST 18%").ratePercent(new BigDecimal("18"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(new BigDecimal("360.00"))
                .amount(new BigDecimal("360.00")).visibleToCustomer(true).build());

        String t = text(AirInvoicePdfRenderer.write(invoice(), List.of(rupees), igst, agency(), Map.of("V1", "LVBJAY"), null, null));

        assertThat(t).contains("IGST 18%", "360.00").doesNotContain("CGST").doesNotContain("Total Amount in AED");
    }

    @Test
    void anInvoiceBilledInAnotherCurrencyPrintsItsTotalAndEachGstRowInBothCurrencies() throws Exception {
        Invoice aed = invoice();
        aed.setCurrencyCode("AED");
        aed.setFxRateToInr(new BigDecimal("26.5"));
        aed.setTaxableValue(new BigDecimal("5320.60"));
        aed.setGrandTotal(new BigDecimal("5334.18"));
        aed.setGrandTotalInr(new BigDecimal("141355.77"));
        InvoiceLineItem line = ticket();
        line.setTaxableValue(new BigDecimal("5320.60"));
        line.setCharges(Map.of("basic", new BigDecimal("4905.66"), "yq", new BigDecimal("339.47")));
        List<InvoiceTax> gst = List.of(InvoiceTax.builder().label("GST 18%").ratePercent(new BigDecimal("18"))
                .cgstAmount(new BigDecimal("6.79")).sgstAmount(new BigDecimal("6.79")).igstAmount(BigDecimal.ZERO)
                .amount(new BigDecimal("13.58")).visibleToCustomer(true).build());

        String t = text(AirInvoicePdfRenderer.write(aed, List.of(line), gst, agency(), Map.of("V1", "LVBJAY"), null, null));

        assertThat(t).contains("5320.60", "CGST 9%", "6.79  (INR 179.94)", "Total Amount in INR", "141355.77",
                "Total Amount in AED (@ 26.5)", "5334.18", "Five Thousand Three Hundred Thirty Four AED");
    }

    @Test
    void onlyAnAirInvoiceDraftedFromTicketsWithFlightsTakesThisLayout() {
        InvoiceLineItem old = InvoiceLineItem.builder().description("BOM to DXB").taxableValue(BigDecimal.TEN).build();
        assertThat(AirInvoicePdfRenderer.applies(InvoiceServiceCategory.AIR_INTERNATIONAL, List.of(ticket()))).isTrue();
        assertThat(AirInvoicePdfRenderer.applies(InvoiceServiceCategory.AIR_INTERNATIONAL, List.of(old))).isFalse();
        assertThat(AirInvoicePdfRenderer.applies(InvoiceServiceCategory.HOTEL, List.of(ticket()))).isFalse();
    }

    @Test
    void thePanIsReadFromTheGstin() {
        assertThat(AirInvoicePdfRenderer.pan("23AFFFS1126P1ZV")).isEqualTo("AFFFS1126P");
        assertThat(AirInvoicePdfRenderer.pan(null)).isNull();
        assertThat(AirInvoicePdfRenderer.cabin("Premium Economy")).isEqualTo("PEC");
    }
}
