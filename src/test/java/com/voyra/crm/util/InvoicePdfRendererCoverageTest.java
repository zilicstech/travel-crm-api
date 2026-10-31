package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceServiceCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the invoice PDF prints for each optional block, read back from the rendered PDF. */
class InvoicePdfRendererCoverageTest {

    private static Invoice invoice() {
        return Invoice.builder().id("I1").invoiceNumber("INV-0001").documentType(InvoiceDocumentType.TAX_INVOICE)
                .clientName("Client Alpha").agencyLegalName("Agency Legal Ltd").agencyAddress("Agency Street")
                .agencyGstin("27BBBBB1111B1Z1").agencyStateCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("1000.00")).roundOff(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("1000.00"))
                .invoiceDate(LocalDate.of(2026, 9, 19)).build();
    }

    private static InvoiceLineItem line() {
        return InvoiceLineItem.builder().id("L1").description("Line One").sacCode("9985").quantity(new BigDecimal("2.00"))
                .lineSubtotal(new BigDecimal("1000.00")).lineTotal(new BigDecimal("1000.00")).build();
    }

    private static String render(Invoice invoice, List<InvoiceLineItem> lines, List<InvoiceTax> taxes, Tenant agency,
                                 Booking booking, List<BookingCostComponent> components, List<String> voucherRefs,
                                 List<String> bookingRefs) {
        return PdfText.of(InvoicePdfRenderer.write(invoice, lines, taxes, agency, booking, components, voucherRefs, bookingRefs));
    }

    private static String render(Invoice invoice) {
        return render(invoice, List.of(line()), List.of(), null, null, List.of(), List.of(), List.of());
    }

    @Test
    void aBareInvoicePrintsDashesForMissingFieldsAndNoOptionalBlocks() {
        Invoice inv = invoice().toBuilder().agencyLegalName(null).agencyAddress(null).agencyGstin(" ")
                .agencyStateCode(null).invoiceNumber(null).invoiceDate(null).build();

        String t = render(inv);

        assertThat(t).contains("TAX INVOICE", "GSTIN: -", "State: -", "No: -", "Date: -", "Due: -", "Currency: INR",
                "Bill To", "Client Alpha", "Description", "SAC", "Qty", "Amount", "Line One", "9985",
                "Total Amount in INR", "1000.00");
        assertThat(t).doesNotContain("Bank Details", "Notes:", "Terms:", "GSTIN: 27", "Place of Supply", "Round Off",
                "locked @", "Bookings:", "PNR", "Fare");
    }

    @Test
    void aProformaIsTitledPaymentRequest() {
        assertThat(render(invoice().toBuilder().documentType(InvoiceDocumentType.PROFORMA).build()))
                .contains("PAYMENT REQUEST");
    }

    @Test
    void theServiceCategoryNamesTheDocument() {
        assertThat(render(invoice().toBuilder().serviceCategory(InvoiceServiceCategory.HOTEL).build()))
                .contains("HOTEL INVOICE");
    }

    @ParameterizedTest
    @CsvSource({
            "AIR_INTERNATIONAL, PNR: REF1",
            "AIR_DOMESTIC, PNR: REF1",
            "RAIL, PNR: REF1",
            "HOTEL, Confirmation No: REF1",
            "VISA, Application No: REF1",
            "TRANSPORT, Voucher No: REF1"})
    void theBookingsOwnReferencePrintsUnderTheTitle(InvoiceServiceCategory category, String expected) {
        Booking booking = Booking.builder().pnr("REF1").hotelConfirmationNo("REF1").visaApplicationNo("REF1")
                .transferVoucherNo("REF1").build();

        String t = render(invoice().toBuilder().serviceCategory(category).build(), List.of(line()), List.of(), null,
                booking, List.of(), List.of(), List.of());

        assertThat(t).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"AIR_INTERNATIONAL", "AIR_DOMESTIC", "RAIL", "HOTEL", "VISA", "TRANSPORT", "PACKAGE", "MISCELLANEOUS"})
    void aBookingWithoutTheCategorysReferencePrintsNoReferenceLine(InvoiceServiceCategory category) {
        String t = render(invoice().toBuilder().serviceCategory(category).build(), List.of(line()), List.of(), null,
                Booking.builder().build(), List.of(), List.of(), List.of());

        assertThat(t).doesNotContain("PNR:", "Confirmation No:", "Application No:", "Voucher No:", "Reference:");
    }

    @Test
    void aCategoryWithoutABookingPrintsNoReferenceLine() {
        String t = render(invoice().toBuilder().serviceCategory(InvoiceServiceCategory.HOTEL).build());

        assertThat(t).doesNotContain("Confirmation No:");
    }

    @Test
    void aBookingWithoutACategoryPrintsNoReferenceLine() {
        String t = render(invoice(), List.of(line()), List.of(), null, Booking.builder().pnr("REF1").build(), List.of(),
                List.of(), List.of());

        assertThat(t).doesNotContain("REF1");
    }

    @ParameterizedTest
    @CsvSource({
            "AIR_INTERNATIONAL, PNR: V1, V2",
            "AIR_DOMESTIC, PNR: V1, V2",
            "RAIL, PNR: V1, V2",
            "HOTEL, Confirmation No: V1, V2",
            "VISA, Application No: V1, V2",
            "TRANSPORT, Voucher No: V1, V2",
            "PACKAGE, Reference: V1, V2",
            "MISCELLANEOUS, Reference: V1, V2"})
    void voucherReferencesPrintInPlaceOfTheBookingsWithTheCategorysLabel(InvoiceServiceCategory category, String expected) {
        String t = render(invoice().toBuilder().serviceCategory(category).build(), List.of(line()), List.of(), null,
                Booking.builder().pnr("BOOKINGPNR").build(), List.of(), List.of("V1", "V2"), List.of());

        assertThat(t).contains(expected).doesNotContain("BOOKINGPNR");
    }

    @Test
    void voucherReferencesWithoutACategoryAreLabelledReference() {
        String t = render(invoice(), List.of(line()), List.of(), null, null, List.of(), List.of("V9"), List.of());

        assertThat(t).contains("Reference: V9");
    }

    @Test
    void severalBookingsAreListedButASingleOneIsNot() {
        assertThat(render(invoice(), List.of(line()), List.of(), null, null, List.of(), List.of(), List.of("BK-1", "BK-2")))
                .contains("Bookings: BK-1, BK-2");
        assertThat(render(invoice(), List.of(line()), List.of(), null, null, List.of(), List.of(), List.of("BK-1")))
                .doesNotContain("Bookings:");
    }

    @Test
    void aForeignCurrencyInvoiceShowsTheLockedRateAndBothTotals() {
        Invoice inv = invoice().toBuilder().currencyCode("USD").fxRateToInr(new BigDecimal("83.5"))
                .grandTotal(new BigDecimal("12.00")).grandTotalInr(new BigDecimal("1002.00")).build();

        String t = render(inv);

        assertThat(t).contains("Currency: USD (locked @ 83.5)", "Total Amount in INR", "1002.00", "Total Amount in USD", "12.00");
    }

    @Test
    void theAgencysBaseCurrencyReplacesInr() {
        Tenant agency = new Tenant();
        agency.setBaseCurrencyCode("AED");
        Invoice inv = invoice().toBuilder().currencyCode("AED").build();

        String t = render(inv, List.of(line()), List.of(), agency, null, List.of(), List.of(), List.of());

        assertThat(t).contains("Currency: AED", "Total Amount in AED").doesNotContain("locked @", "Total Amount in INR");
    }

    @Test
    void anAgencyWithoutABaseCurrencyFallsBackToInr() {
        Tenant agency = new Tenant();
        agency.setBaseCurrencyCode(null);

        assertThat(InvoicePdfRenderer.base(agency)).isEqualTo("INR");
        assertThat(InvoicePdfRenderer.base(null)).isEqualTo("INR");
    }

    @Test
    void theBillToBlockPrintsTheAddressAndGstFieldsWhenPresent() {
        Invoice inv = invoice().toBuilder().billingAddress("Client Road").placeOfSupplyCode("27").clientGstin(null).build();

        String t = render(inv);

        assertThat(t).contains("Client Road", "GSTIN: -   Place of Supply: 27   Treatment: -");
    }

    @Test
    void aBlankBillingAddressIsNotPrinted() {
        String blank = render(invoice().toBuilder().billingAddress("   ").build());

        assertThat(blank).isEqualTo(render(invoice().toBuilder().billingAddress(null).build()));
    }

    @Test
    void notesAndTermsPrintOnlyWhenNotBlank() {
        assertThat(render(invoice().toBuilder().notes("Note one").terms("Term one").build()))
                .contains("Notes: Note one", "Terms: Term one");
        assertThat(render(invoice().toBuilder().notes(" ").terms(" ").build()))
                .doesNotContain("Notes:", "Terms:");
    }

    @Test
    void aFareSplitLinePrintsFareAndTaxColumnsAndItsCharges() {
        InvoiceLineItem split = line().toBuilder().fareAmount(new BigDecimal("900")).taxAmount(null)
                .sacCode(null).charges(Map.of("basic", new BigDecimal("800.00"))).build();

        String t = render(invoice(), List.of(split), List.of(), null, null, List.of(), List.of(), List.of());

        assertThat(t).contains("Fare", "Taxes", "900.00", "0.00", "Basic 800.00", "Markup 200.00");
    }

    @Test
    void noLinesPrintsTheHeaderOnly() {
        String t = render(invoice(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());

        assertThat(t).contains("Description").doesNotContain("Fare", "Line One");
    }

    @Test
    void hiddenTaxesFoldIntoTheAmountAndVisibleNonZeroTaxesGetTheirOwnLine() {
        List<InvoiceTax> taxes = List.of(
                InvoiceTax.builder().label("Hidden Tax").amount(new BigDecimal("50.00")).visibleToCustomer(false).build(),
                InvoiceTax.builder().label("Null Visibility").amount(new BigDecimal("25.00")).visibleToCustomer(null).build(),
                InvoiceTax.builder().label("GST 18%").amount(new BigDecimal("180.00")).visibleToCustomer(true).build(),
                InvoiceTax.builder().label("Zero Tax").amount(BigDecimal.ZERO).visibleToCustomer(true).build());
        Invoice inv = invoice().toBuilder().roundOff(new BigDecimal("0.40")).build();

        String t = render(inv, List.of(line()), taxes, null, null, List.of(), List.of(), List.of());

        assertThat(t).contains("1075.00", "GST 18%", "180.00", "Round Off", "0.40");
        assertThat(t).doesNotContain("Hidden Tax", "Null Visibility", "Zero Tax");
    }

    @Test
    void aCommissionAgentInvoiceListsEachCostComponentThenTheAgencyFee() {
        List<BookingCostComponent> components = new ArrayList<>(List.of(
                BookingCostComponent.builder().vendorName("Vendor A").description("Flights").netCostInr(new BigDecimal("600.00")).build(),
                BookingCostComponent.builder().vendorName(null).description(" ").netCostInr(new BigDecimal("100.00")).build(),
                BookingCostComponent.builder().vendorName("Vendor C").description(null).netCostInr(null).build()));
        Invoice inv = invoice().toBuilder().billingModel(InvoiceBillingModel.COMMISSION_AGENT).build();

        String t = render(inv, List.of(line()), List.of(), null, null, components, List.of(), List.of());

        assertThat(t).contains("Supplier disbursement (reimbursement - no GST)", "Vendor A - Flights600.00",
                "Supplier 100.00", "Vendor C0.00", "Agency service fee300.00",
                "GST applies");
    }

    @Test
    void aCommissionAgentInvoiceWithoutComponentsPassesThroughItsOwnAmount() {
        Invoice inv = invoice().toBuilder().billingModel(InvoiceBillingModel.COMMISSION_AGENT)
                .passThroughAmount(new BigDecimal("400.00")).build();

        String t = render(inv);

        assertThat(t).contains("Supplier cost400.00", "Agency service fee600.00");
    }

    @Test
    void aCommissionAgentInvoiceWithNoPassThroughChargesTheWholeTaxableValueAsFee() {
        Invoice inv = invoice().toBuilder().billingModel(InvoiceBillingModel.COMMISSION_AGENT).passThroughAmount(null).build();

        String t = render(inv);

        assertThat(t).contains("Supplier cost0.00", "Agency service fee1000.00");
    }

    @Test
    void aFeeBelowZeroPrintsAsZero() {
        Invoice inv = invoice().toBuilder().billingModel(InvoiceBillingModel.COMMISSION_AGENT)
                .passThroughAmount(new BigDecimal("1500.00")).build();

        String t = render(inv);

        assertThat(t).contains("Supplier cost", "1500.00", "Agency service fee0.00");
    }

    @Test
    void anAgencyWithABankAccountPrintsTheBankBlockAndAQrCaption() {
        Tenant agency = new Tenant();
        agency.setBankAccountName("Agency Account");
        agency.setBankAccountNumber("000111222");
        agency.setBankIfscCode("TEST0000001");
        agency.setBankBranch("Main Branch");

        String t = render(invoice(), List.of(line()), List.of(), agency, null, List.of(), List.of(), List.of());

        assertThat(t).contains("Bank Details", "Agency Account", "Account Number: 000111222", "IFSC: TEST0000001",
                "Branch: Main Branch", "Scan for bank details");
    }

    @Test
    void theBankBlockSkipsBlankOptionalFields() {
        Tenant agency = new Tenant();
        agency.setBankAccountName(" ");
        agency.setBankAccountNumber("000111222");
        agency.setBankIfscCode(null);
        agency.setBankBranch("");

        String t = render(invoice(), List.of(line()), List.of(), agency, null, List.of(), List.of(), List.of());

        assertThat(t).contains("Bank Details", "Account Number: 000111222", "Scan for bank details")
                .doesNotContain("IFSC:", "Branch:");
    }

    @Test
    void anAgencyWithoutABankAccountPrintsNoBankBlock() {
        Tenant agency = new Tenant();
        agency.setBankAccountNumber(" ");

        assertThat(render(invoice(), List.of(line()), List.of(), agency, null, List.of(), List.of(), List.of()))
                .doesNotContain("Bank Details", "Scan for bank details");
    }

    @Test
    void theSixArgumentOverloadPrintsNoReferences() {
        String t = PdfText.of(InvoicePdfRenderer.write(invoice(), List.of(line()), List.of(), null, null, List.of()));

        assertThat(t).contains("TAX INVOICE").doesNotContain("Bookings:", "Reference:");
    }

    @Test
    void helpersFormatMissingValues() {
        assertThat(InvoicePdfRenderer.money(null)).isEqualTo("0.00");
        assertThat(InvoicePdfRenderer.money(new BigDecimal("1.005"))).isEqualTo("1.01");
        assertThat(InvoicePdfRenderer.valueOr(null)).isEqualTo("-");
        assertThat(InvoicePdfRenderer.valueOr(" ")).isEqualTo("-");
        assertThat(InvoicePdfRenderer.valueOr("x")).isEqualTo("x");
        assertThat(InvoicePdfRenderer.str(null)).isEqualTo("-");
        assertThat(InvoicePdfRenderer.str(5)).isEqualTo("5");
    }
}
