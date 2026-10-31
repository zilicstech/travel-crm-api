package com.voyra.crm.util;

import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.PaxType;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The optional parts of the air and hotel invoice layouts, read back from the rendered PDF. */
class AirInvoicePdfRendererCoverageTest {

    private static Invoice bare() {
        return Invoice.builder().currencyCode("INR").taxableValue(new BigDecimal("100.00"))
                .grandTotal(new BigDecimal("100.00")).grandTotalInr(new BigDecimal("100.00")).build();
    }

    private static InvoiceLineItem line(String docId) {
        return InvoiceLineItem.builder().bookingDocumentId(docId).description("Line Desc").taxableValue(new BigDecimal("100.00")).build();
    }

    private static String air(Invoice inv, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                              Map<String, String> refs, byte[] logo, String preparedBy) {
        return PdfText.flat(AirInvoicePdfRenderer.write(inv, lines, taxes, agency, refs, logo, preparedBy));
    }

    private static String[] f(String... parts) {
        return PdfText.flat(parts);
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void onlyAirCategoriesWithFlightsOrHotelsWithVouchersTakeTheseLayouts() {
        VoucherLeg leg = VoucherLeg.builder().from("AAA").to("BBB").build();
        InvoiceLineItem withLegs = line("V1").toBuilder().legs(List.of(leg)).build();
        InvoiceLineItem emptyLegs = line("V1").toBuilder().legs(List.of()).build();

        assertThat(AirInvoicePdfRenderer.applies(InvoiceServiceCategory.AIR_DOMESTIC, List.of(withLegs))).isTrue();
        assertThat(AirInvoicePdfRenderer.applies(InvoiceServiceCategory.AIR_DOMESTIC, List.of(emptyLegs))).isFalse();
        assertThat(AirInvoicePdfRenderer.applies(null, List.of(withLegs))).isFalse();
        assertThat(AirInvoicePdfRenderer.appliesToHotel(InvoiceServiceCategory.AIR_DOMESTIC, List.of(line("V1")))).isFalse();
        assertThat(AirInvoicePdfRenderer.appliesToHotel(InvoiceServiceCategory.HOTEL, List.of(line(null)))).isFalse();
    }

    @Test
    void aBareInvoiceFallsBackEverywhere() {
        String t = air(bare(), List.of(line(null)), List.of(), null, new HashMap<>(), null, null);

        assertThat(t).contains(f("-", "Country: India.", "To", "Tax Invoice", "Line Desc", "100", "Gross Total100.00",
                "One Hundred Rupees Only", "Total Amount in INR100.00", "Terms :", "E. & O. E.", "for", "(ID: )"));
        assertThat(t).doesNotContain(f("Airline:", "(PAN)", "(GSTIN)", "(M)", "(w)", "(e)", "Place of Supply", "Prepared by",
                "ACCOUNT NUMBER", "Round Off", "Other charges", "Airline PNR ("));
    }

    @Test
    void theAgencyRecordFillsTheHeaderWhenTheInvoiceHasNoLegalName() {
        Tenant agency = new Tenant();
        agency.setAgencyName("Agency Name");
        agency.setInvoiceTerms("Agency terms apply");
        Invoice inv = bare().toBuilder().agencyStateCode("99").agencyGstin("SHORT").id("abcd-ef").terms(" ").build();

        String t = air(inv, List.of(line(null)), List.of(), agency, new HashMap<>(), null, null);

        assertThat(t).contains(f("AGENCY NAME", "State: 99 (State Code: 99), Country: India.", "(GSTIN) SHORT",
                "Agency terms apply", "for AGENCY NAME", "(ID: ABCDEF)"));
        assertThat(t).doesNotContain(f("(PAN)"));
    }

    @Test
    void anAgencyWithoutTermsOrNameLeavesThemEmpty() {
        Tenant agency = new Tenant();
        agency.setAgencyName("");
        Invoice inv = bare().toBuilder().id("0123456789abcdef").build();

        String t = air(inv, List.of(line(null)), List.of(), agency, new HashMap<>(), null, null);

        assertThat(t).contains(f("Terms :", "(ID: 01234567)"));
    }

    @Test
    void aReadableLogoIsPrintedAndAnUnreadableOneIsSkipped() throws Exception {
        Tenant agency = new Tenant();
        agency.setAgencyName("Logo Agency");
        byte[] withLogo = AirInvoicePdfRenderer.write(bare(), List.of(line(null)), List.of(), agency, new HashMap<>(), png(), null);
        byte[] badLogo = AirInvoicePdfRenderer.write(bare(), List.of(line(null)), List.of(), agency, new HashMap<>(), new byte[]{1, 2, 3}, null);

        assertThat(PdfText.flat(withLogo)).contains("LOGOAGENCY");
        assertThat(PdfText.flat(badLogo)).contains("LOGOAGENCY");
        assertThat(new String(withLogo, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("/Subtype/Image");
        assertThat(new String(badLogo, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("/Subtype/Image");
    }

    @Test
    void theBillToBoxPrintsTheClientsGstinAndStateAndTheSupplyFallsBackToTheClientsState() {
        Invoice inv = bare().toBuilder().clientName("Client One").billingAddress(" ").clientStateCode("27")
                .clientGstin("27AAAAA0000A1Z5").invoiceNumber("ITI1").invoiceDate(LocalDate.of(2026, 1, 5)).build();

        String t = air(inv, List.of(line(null)), List.of(), null, new HashMap<>(), null, "Preparer");

        assertThat(t).contains(f("Client One", "State: Maharashtra (State Code: 27)", "(GSTIN) 27AAAAA0000A1Z5", "ITI1",
                "05 Jan 2026", "Place of Supply: Maharashtra (27)", "(Prepared by: Preparer)"));
    }

    @Test
    void aBlankClientGstinIsNotPrinted() {
        Invoice inv = bare().toBuilder().clientName("Client One").clientGstin(" ").build();

        assertThat(air(inv, List.of(line(null)), List.of(), null, new HashMap<>(), null, null)).doesNotContain("(GSTIN)");
    }

    @Test
    void flightRowsPadPassengersAndLegsAndSplitTheFare() {
        VoucherLeg full = VoucherLeg.builder().from("AAA").to("BBB").airline("Air One").flightNumber("AO 1")
                .cabin("Superior").travelDate(LocalDate.of(2026, 3, 4)).build();
        VoucherLeg sparse = VoucherLeg.builder().airline(" ").cabin("Y").build();
        VoucherLeg noAirline = VoucherLeg.builder().airline(null).build();
        InvoiceLineItem twoLegsOnePax = line("V1").toBuilder().taxableValue(new BigDecimal("1400.50"))
                .charges(Map.of("basic", new BigDecimal("1000"))).legs(List.of(full, sparse, noAirline))
                .travellers(List.of(new VoucherTraveller("M1", "Pax Alpha", PaxType.ADULT))).build();
        InvoiceLineItem twoPaxOneLeg = line("V2").toBuilder().taxableValue(new BigDecimal("500"))
                .legs(List.of(full)).travellers(List.of(new VoucherTraveller("M2", "Pax Beta", PaxType.ADULT),
                        new VoucherTraveller("M3", null, PaxType.CHILD))).build();
        Map<String, String> refs = new HashMap<>();
        refs.put("V1", "PNR1");
        refs.put("V2", " ");

        String t = air(bare(), List.of(twoLegsOnePax, twoPaxOneLeg), List.of(), null, refs, null, null);

        assertThat(t).contains(f("Airline: Air One", "Airline PNR (PNR1)", "001 PAX ALPHA", "AAA  BBB  SUP", "AO 1",
                "04/03/26", "PNR1", "1000", "400.50", "1400.50", "002 PAX BETA", "003", "500", "Y"));
    }

    @Test
    void aTicketWithNoTravellersOrLegsPrintsItsDescription() {
        InvoiceLineItem none = line("V1").toBuilder().description(null).legs(null).travellers(null).build();

        String t = air(bare(), List.of(none), List.of(), null, new HashMap<>(), null, null);

        assertThat(t).contains(f("Passenger", "Sector", "100", "100.00")).doesNotContain(f("Airline:"));
    }

    @Test
    void taxesPrintAsGstSplitsHiddenChargesAndOwnLabels() {
        List<InvoiceTax> taxes = List.of(
                InvoiceTax.builder().label("Hidden").amount(new BigDecimal("5.00")).visibleToCustomer(false).build(),
                InvoiceTax.builder().label("Null Vis").amount(null).visibleToCustomer(null).build(),
                InvoiceTax.builder().label("Zero").amount(null).visibleToCustomer(true).build(),
                InvoiceTax.builder().label("Cgst No Rate").amount(new BigDecimal("2.00")).cgstAmount(null)
                        .sgstAmount(new BigDecimal("2.00")).visibleToCustomer(true).build(),
                InvoiceTax.builder().label("Igst No Rate").amount(new BigDecimal("3.00")).cgstAmount(BigDecimal.ZERO)
                        .sgstAmount(null).igstAmount(new BigDecimal("3.00")).visibleToCustomer(true).build(),
                InvoiceTax.builder().label("Igst Rated").ratePercent(new BigDecimal("12")).amount(new BigDecimal("4.00"))
                        .igstAmount(new BigDecimal("4.00")).visibleToCustomer(true).build(),
                InvoiceTax.builder().label("Service Levy").amount(new BigDecimal("7.00")).igstAmount(null).visibleToCustomer(true).build());
        Invoice inv = bare().toBuilder().roundOff(new BigDecimal("0.30")).build();

        String t = air(inv, List.of(line(null)), taxes, null, new HashMap<>(), null, null);

        assertThat(t).contains(f("CGST0.00", "SGST2.00", "IGST3.00", "IGST 12%4.00", "Service Levy7.00",
                "Other charges5.00", "Round Off0.30"));
        assertThat(t).doesNotContain(f("Hidden", "Null Vis", "Zero", "CGST 6%"));
    }

    @Test
    void aForeignInvoiceWithoutARatePrintsAnEmptyRate() {
        Invoice inv = bare().toBuilder().currencyCode("USD").fxRateToInr(null).grandTotal(new BigDecimal("2.00")).build();

        assertThat(air(inv, List.of(line(null)), List.of(), null, new HashMap<>(), null, null))
                .contains(f("Total Amount in USD (@ )2.00", "Total Amount in INR100.00"));
    }

    @Test
    void anInvoiceWithoutACurrencyIsTakenAsBase() {
        Invoice inv = bare().toBuilder().currencyCode(null).grandTotalInr(null).build();

        assertThat(air(inv, List.of(line(null)), List.of(), null, new HashMap<>(), null, null))
                .contains(f("Total Amount in INR0.00")).doesNotContain(f("(@"));
    }

    @Test
    void theBankBlockPrintsOnlyTheFieldsTheAgencyHas() {
        Tenant full = new Tenant();
        full.setAgencyName("Bank Agency");
        full.setBankAccountName("Acct Name");
        full.setBankAccountNumber("12345");
        full.setBankIfscCode("TEST0000004");
        full.setBankBranch("Branch One");
        Tenant minimal = new Tenant();
        minimal.setAgencyName("Bank Agency");
        minimal.setBankAccountNumber("12345");
        Tenant blank = new Tenant();
        blank.setAgencyName("Bank Agency");
        blank.setBankAccountNumber(" ");

        assertThat(air(bare(), List.of(line(null)), List.of(), full, new HashMap<>(), null, null))
                .contains(f("Acct Name", "ACCOUNT NUMBER - 12345", "IFSC CODE - TEST0000004", "BRANCH - Branch One"));
        assertThat(air(bare(), List.of(line(null)), List.of(), minimal, new HashMap<>(), null, null))
                .contains(f("ACCOUNT NUMBER - 12345")).doesNotContain(f("IFSC CODE", "BRANCH -"));
        assertThat(air(bare(), List.of(line(null)), List.of(), blank, new HashMap<>(), null, null)).doesNotContain(f("ACCOUNT NUMBER"));
    }

    @Test
    void aHotelBlockFallsBackForMissingDetailsAndBadDates() {
        Invoice inv = bare().toBuilder().clientName("Client Two").serviceCategory(InvoiceServiceCategory.HOTEL).build();
        Map<String, String> partial = new HashMap<>();
        partial.put("hotelName", "Hotel One");
        partial.put("hotelCity", " ");
        partial.put("hotelCheckIn", "2026-12-05T12:00:00");
        partial.put("hotelCheckOut", "2026-12-01");
        partial.put("hotelRooms", " ");
        partial.put("hotelRoomType", null);
        partial.put("hotelBoardBasis", "Room Only");
        Map<String, String> badDates = new HashMap<>();
        badDates.put("hotelCheckIn", "not-a-date");
        badDates.put("hotelCheckOut", " ");
        InvoiceLineItem noDoc = line(null).toBuilder().description("No Doc Stay").build();
        InvoiceLineItem noDetails = line("V9").toBuilder().description("Unknown Stay").build();
        InvoiceLineItem withPartial = line("V1");
        InvoiceLineItem withBadDates = line("V2").toBuilder().description("Bad Dates Stay").build();

        String t = PdfText.flat(AirInvoicePdfRenderer.writeHotel(inv, List.of(noDoc, noDetails, withPartial, withBadDates),
                List.of(), null, new HashMap<>(Map.of("V1", "CONF1")), Map.of("V1", partial, "V2", badDates), null, null));

        assertThat(t).contains(f("Hotel Invoice", "Confirmation No. (CONF1)", "No Doc Stay", "Unknown Stay", "CLIENT TWO",
                "Hotel One", "05/12/26", "01/12/26", "Room Only", "CONF1", "Bad Dates Stay",
                "(Reimbursement of hotel accommodation)"));
        assertThat(t).doesNotContain(f("Hotel One,", "x Room"));
    }

    @Test
    void aMultiGuestStayListsEveryGuestButTheStayOnce() {
        Map<String, String> details = Map.of("hotelName", "Hotel Two", "hotelCheckIn", "2026-12-01", "hotelCheckOut", "2026-12-03");
        InvoiceLineItem stay = line("V1").toBuilder().travellers(List.of(
                new VoucherTraveller("M1", "Guest One", PaxType.ADULT), new VoucherTraveller("M2", "Guest Two", PaxType.ADULT))).build();

        String t = PdfText.flat(AirInvoicePdfRenderer.writeHotel(bare(), List.of(stay), List.of(), null, new HashMap<>(),
                Map.of("V1", details), null, null));

        assertThat(t).contains(f("001 GUEST ONE", "Hotel Two", "01/12/26", "03/12/26", "2", "100.00", "002 GUEST TWO"));
    }

    @Test
    void aTicketWithLegsButNoTravellersNamesItsFirstRowOnly() {
        InvoiceLineItem legsOnly = line("V1").toBuilder().description("Legs Only").travellers(List.of()).legs(List.of(
                VoucherLeg.builder().from("AAA").to("BBB").build(), VoucherLeg.builder().from("BBB").to("CCC").build())).build();

        String t = PdfText.of(AirInvoicePdfRenderer.write(bare(), List.of(legsOnly), List.of(), null, new HashMap<>(), null, null));

        assertThat(t).containsOnlyOnce("Legs Only");
        assertThat(PdfText.flat(t)).contains(f("AAA  BBB", "BBB  CCC"));
    }

    @Test
    void aStayWithoutACheckOutHasNoNightsAndBlankRoomFieldsAreDropped() {
        Map<String, String> details = new HashMap<>();
        details.put("hotelName", "Hotel Three");
        details.put("hotelCheckIn", "2026-12-01");
        details.put("hotelRooms", "1");
        details.put("hotelRoomType", " ");
        details.put("hotelBoardBasis", "Half Board");

        String t = PdfText.flat(AirInvoicePdfRenderer.writeHotel(bare(), List.of(line("V1")), List.of(), null, new HashMap<>(),
                Map.of("V1", details), null, null));

        assertThat(t).contains(f("Hotel Three", "01/12/26", "1 x Half Board")).doesNotContain(f("·"));
    }

    @Test
    void cabinCodesAndPanHelpers() {
        assertThat(AirInvoicePdfRenderer.cabin(null)).isEmpty();
        assertThat(AirInvoicePdfRenderer.cabin(" ")).isEmpty();
        assertThat(AirInvoicePdfRenderer.cabin(" business ")).isEqualTo("BUS");
        assertThat(AirInvoicePdfRenderer.cabin("Superior")).isEqualTo("SUP");
        assertThat(AirInvoicePdfRenderer.cabin("j")).isEqualTo("J");
        assertThat(AirInvoicePdfRenderer.pan(" 23afffs1126p1zv ")).isEqualTo("AFFFS1126P");
        assertThat(AirInvoicePdfRenderer.pan("23AFFF")).isNull();
    }
}
