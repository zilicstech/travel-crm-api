package com.voyra.crm.util;

import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.models.ReferenceFare;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The edges of the small util helpers that their own tests leave open. */
class UtilEdgeCasesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 6, 5);

    // ---------------------------------------------------------------- credit note and receipt PDFs

    private static CreditNote note() {
        return CreditNote.builder().creditNoteNumber("CN-1").invoiceNumber("INV-1").clientName("Client A")
                .reason(CreditNoteReason.BOOKING_CANCELLED).currencyCode("INR").taxableValue(new BigDecimal("100.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(new BigDecimal("18.00"))
                .tcsAmount(new BigDecimal("1.00")).cancellationFee(new BigDecimal("10.00"))
                .totalAmount(new BigDecimal("119.00")).refundableAmount(new BigDecimal("109.00")).refundedAmount(BigDecimal.ZERO).build();
    }

    @Test
    void aCreditNoteWithIgstTcsAndAFeePrintsEachReversal() {
        String t = PdfText.flat(CreditNotePdfRenderer.write(note()));

        assertThat(t).contains(PdfText.flat("Reason", "BOOKING_CANCELLED", "IGST Reversed18.00", "TCS Reversed1.00",
                "Retained (still taxable)10.00", "Total Amount119.00 INR", "Refundable109.00"));
        assertThat(t).doesNotContain(PdfText.flat("CGST Reversed", "BOOKING_CANCELLED -"));
    }

    @Test
    void aCreditNoteWithoutIgstTcsOrFeePrintsCgstAndSgstOnly() {
        CreditNote intra = note().toBuilder().reasonNote("Client asked").igstAmount(BigDecimal.ZERO)
                .cgstAmount(new BigDecimal("9.00")).sgstAmount(new BigDecimal("9.00")).tcsAmount(BigDecimal.ZERO)
                .cancellationFee(BigDecimal.ZERO).build();

        String t = PdfText.flat(CreditNotePdfRenderer.write(intra));

        assertThat(t).contains(PdfText.flat("BOOKING_CANCELLED - Client asked", "CGST Reversed9.00", "SGST Reversed9.00"));
        assertThat(t).doesNotContain(PdfText.flat("IGST Reversed", "TCS Reversed", "Retained"));
    }

    private static PaymentReceipt receipt() {
        return PaymentReceipt.builder().receiptNumber("RCP-1").currencyCode("USD").amount(new BigDecimal("10.00"))
                .amountInr(new BigDecimal("835.00")).paymentMode(PaymentMode.CASH).notes("Paid at desk").build();
    }

    @Test
    void aForeignReceiptShowsItsBaseAmountAndNotes() {
        String t = PdfText.flat(ReceiptVoucherRenderer.write(receipt(), "INV-1", "INR"));

        assertThat(t).contains(PdfText.flat("Amount10.00 USD", "Amount (INR)835.00", "NotesPaid at desk"));
    }

    @Test
    void aBlankNoteIsNotPrinted() {
        String t = PdfText.flat(ReceiptVoucherRenderer.write(receipt().toBuilder().notes(" ").currencyCode("INR").build(), "INV-1", "INR"));

        assertThat(t).doesNotContain(PdfText.flat("Notes", "Amount (INR)"));
    }

    @Test
    void aTableReportPrintsNullCellsEmpty() {
        String t = PdfText.of(PdfTableWriter.write("Report One", new ReportTable(List.of("h1", "h2"), List.of(Arrays.asList(null, "v2")))));

        assertThat(t).contains("Report One", "h1", "v2").doesNotContain("null");
    }

    // ---------------------------------------------------------------- service dates and labels

    @Test
    void aFlightWithNullSectorsHasNoDates() {
        LeadService s = LeadService.builder().type(ServiceType.FLIGHT).flightSectors(null).dateFrom(D1).dateTo(D2).build();

        ServiceDateRangeDeriver.derive(s);

        assertThat(s.getDateFrom()).isNull();
        assertThat(s.getDateTo()).isNull();
    }

    @Test
    void aHotelWithoutCheckInOrNightsKeepsItsCheckOut() {
        LeadService noCheckIn = LeadService.builder().type(ServiceType.HOTEL).hotelCheckOut(D2).hotelNights(3).build();
        LeadService noNights = LeadService.builder().type(ServiceType.HOTEL).hotelCheckIn(D1).hotelNights(null).build();
        LeadService nothing = LeadService.builder().type(ServiceType.HOTEL).hotelNights(2).build();
        ServiceDateRangeDeriver.derive(nothing);
        assertThat(nothing.getDateTo()).isNull();

        ServiceDateRangeDeriver.derive(noCheckIn);
        ServiceDateRangeDeriver.derive(noNights);

        assertThat(noCheckIn.getDateFrom()).isNull();
        assertThat(noCheckIn.getDateTo()).isEqualTo(D2);
        assertThat(noNights.getDateFrom()).isEqualTo(D1);
    }

    @Test
    void visaAndTransferServicesAreLabelledByCountryAndPickup() {
        List<LeadService> services = new ArrayList<>(List.of(
                LeadService.builder().type(ServiceType.VISA).visaCountry("Japan").build(),
                LeadService.builder().type(ServiceType.VISA).visaCountry("Korea").build(),
                LeadService.builder().type(ServiceType.TRANSFER).transferPickup("Airport").build(),
                LeadService.builder().type(ServiceType.TRANSFER).transferPickup(" ").build(),
                LeadService.builder().type(ServiceType.TRANSFER).transferPickup(null).build()));

        ServiceLabelDeriver.deriveAll(services);

        assertThat(services).extracting(LeadService::getLabel).containsExactly(
                "Visa — Japan", "Visa — Korea", "Transfer — Airport", "Transfer 2", "Transfer 3");
    }

    @Test
    void flightsWithoutACompleteSectorFallBackToTheirPosition() {
        List<LeadService> services = new ArrayList<>(List.of(
                LeadService.builder().type(ServiceType.FLIGHT).flightSectors(null).build(),
                LeadService.builder().type(ServiceType.FLIGHT).flightSectors(List.of(
                        new FlightSectorDto(null, "BBB", D1), new FlightSectorDto(" ", "BBB", D1),
                        new FlightSectorDto("AAA", null, D1), new FlightSectorDto("AAA", " ", D1))).build(),
                LeadService.builder().type(ServiceType.FLIGHT).flightSectors(List.of(new FlightSectorDto("AAA", "BBB", D1))).build()));

        ServiceLabelDeriver.deriveAll(services);

        assertThat(services).extracting(LeadService::getLabel).containsExactly("Flight 1", "Flight 2", "Flight — AAA–BBB");
    }

    // ---------------------------------------------------------------- trip rules

    @Test
    void tripRulesAllowMissingDatesAndOrderedOnes() {
        assertThatCode(() -> TripRules.stay(null, D2, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.stay(D1, null, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.flight(null, D1)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.flight(D1, null)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.journey(BookingType.PACKAGE, null, D1)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.journey(BookingType.PACKAGE, D1, null)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.journey(BookingType.PACKAGE, D1, D2)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.sectors(null)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.sectors(Arrays.asList(null, new FlightSectorDto("A", "B", null),
                new FlightSectorDto("A", "B", D1)))).doesNotThrowAnyException();
    }

    @Test
    void aPackageEndingBeforeItStartsIsRefusedWithItsOwnWording() {
        assertThatThrownBy(() -> TripRules.journey(BookingType.PACKAGE, D2, D1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The end date can't be before the start date");
        assertThatThrownBy(() -> TripRules.journey(BookingType.HOTEL, D2, D1))
                .hasMessage("The return date can't be before the departure date");
    }

    // ---------------------------------------------------------------- money helpers

    @Test
    void aBasketMustExistAndEveryFareNeedsAGross() {
        assertThatThrownBy(() -> PricingMath.worstCaseNetPer10k(null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, DiscountBase.GROSS))
                .isInstanceOf(IllegalStateException.class).hasMessage("Reference fare basket is empty");
        assertThatThrownBy(() -> PricingMath.worstCaseNetPer10k(List.of(new ReferenceFare("F0", BigDecimal.ZERO, BigDecimal.ZERO)),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, DiscountBase.GROSS))
                .isInstanceOf(IllegalStateException.class).hasMessage("Reference fare F0 has no gross fare");
    }

    @Test
    void releasingMoreThanIsHeldIsRefused() {
        assertThatThrownBy(() -> WalletMath.apply(new BigDecimal("100"), new BigDecimal("50"), WalletEntryType.HOLD_RELEASE, new BigDecimal("-60")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Wallet posting HOLD_RELEASE -60 would leave balance 100 and held -10");
    }

    @Test
    void amountsInWordsCoverBillionsAndAnUnknownCurrency() {
        assertThat(AmountInWords.forAmount(new BigDecimal("2000000001"), "USD")).startsWith("Two Billion One");
        assertThat(AmountInWords.forAmount(new BigDecimal("5"), "XYZ")).isEqualTo("Five XYZ");
    }

    @Test
    void theNextStepAfterARefundedCancellationIsACrossCheck() {
        Booking supplierOnly = Booking.builder().bookingStatus(BookingStatus.CANCELLED)
                .supplierRefundExpected(new BigDecimal("50")).supplierRefundReceived(new BigDecimal("50")).build();
        Booking checked = supplierOnly.toBuilder().refundCheckedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)).build();

        assertThat(BookingNextStep.of(supplierOnly, 1, "INR")).isEqualTo("Cross-check refunds");
        assertThat(BookingNextStep.of(checked, 1, "INR")).isNull();
    }

    @Test
    void aFreeConfirmedBookingNeedsNoInvoice() {
        Booking free = Booking.builder().bookingStatus(BookingStatus.CONFIRMED).sellingPrice(BigDecimal.ZERO).build();

        assertThat(BookingNextStep.of(free, 1, "INR")).isNull();
    }

    @Test
    void moneyIsInRupeesUnlessAnotherCurrencyIsNamed() {
        assertThat(BookingNextStep.money(new BigDecimal("6000"), null)).isEqualTo("₹6,000");
        assertThat(BookingNextStep.money(new BigDecimal("6000"), "AED")).isEqualTo("AED 6,000");
    }

    // ---------------------------------------------------------------- lookups and parsing

    @Test
    void aLowerCaseThreeLetterNameIsLookedUpAsACity() {
        assertThat(AirportCodeResolver.resolve("goa")).isEqualTo("GOI");
        assertThat(AirportCodeResolver.isValid(null)).isFalse();
        assertThat(AirportCodeResolver.isValid(" ")).isFalse();
        assertThat(AirportCodeResolver.isValid("BOM")).isTrue();
    }

    @Test
    void aBirthdayCheckNeedsAllThreeDates() {
        assertThat(PaxTypeCalculator.crossesPaxBoundary(null, D1, D2)).isFalse();
        assertThat(PaxTypeCalculator.crossesPaxBoundary(D1, null, D2)).isFalse();
        assertThat(PaxTypeCalculator.crossesPaxBoundary(D1, D1, null)).isFalse();
    }

    @Test
    void bookingSortsDefaultToNewestFirstAndBreakTiesByNumber() {
        assertThat(BookingSort.parse(" ")).isEqualTo(Sort.by(Sort.Direction.DESC, "bookingNumber"));
        assertThat(BookingSort.parse(null)).isEqualTo(Sort.by(Sort.Direction.DESC, "bookingNumber"));
        assertThat(BookingSort.parse("bookingNumber,asc")).isEqualTo(Sort.by(Sort.Direction.ASC, "bookingNumber"));
        assertThat(BookingSort.parse("clientName")).isEqualTo(
                Sort.by(Sort.Direction.DESC, "clientName").and(Sort.by(Sort.Direction.DESC, "bookingNumber")));
    }

    @Test
    void voucherDetailsDropNothingAndCutLongValues() {
        assertThat(VoucherDetails.clean(BookingType.HOTEL, null)).isNull();
        assertThat(VoucherDetails.clean(BookingType.HOTEL, java.util.Map.of())).isNull();
        assertThat(VoucherDetails.clean(BookingType.HOTEL, java.util.Map.of("hotelName", "H".repeat(130))).get("hotelName")).hasSize(120);
    }

    @Test
    void namesDifferingOnlyByExtraWordsOrShortFormsAreLikelyTheSame() {
        assertThat(NameSimilarity.likelySame("Acme Travel Corp", "Acme")).isTrue();
        assertThat(NameSimilarity.likelySame("Acme Corporation Global", "Acme Corp")).isTrue();
        assertThat(NameSimilarity.likelySame("Acme Co", "Acme Corp Global")).isFalse();
        assertThat(NameSimilarity.likelySame("Ab Xy", "Ac Xy")).isTrue();
        assertThat(NameSimilarity.likelySame("Acme Corporation", "Acme Corp Ltd")).isTrue();
        assertThat(NameSimilarity.likelySame("Acme Corporation", "Acme Corp")).isTrue();
    }
}
