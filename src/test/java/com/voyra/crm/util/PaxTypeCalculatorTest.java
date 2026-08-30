package com.voyra.crm.util;

import com.voyra.crm.enums.PaxType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pax type is derived from date of birth against the TRAVEL date, never stored. These are the
 * boundary cases that make that necessary - get them wrong and the agency quotes a child fare
 * for a passenger the airline will price as an adult.
 */
class PaxTypeCalculatorTest {

    private static final LocalDate TRAVEL = LocalDate.of(2026, 9, 15);

    @Test
    void underTwoOnTheTravelDateIsAnInfant() {
        assertThat(PaxTypeCalculator.paxTypeAt(LocalDate.of(2025, 1, 1), TRAVEL)).isEqualTo(PaxType.INFANT);
    }

    @Test
    void exactlyTwoOnTheTravelDateIsAChildNotAnInfant() {
        assertThat(PaxTypeCalculator.paxTypeAt(TRAVEL.minusYears(2), TRAVEL)).isEqualTo(PaxType.CHILD);
    }

    @Test
    void oneDayShortOfTwoIsStillAnInfant() {
        assertThat(PaxTypeCalculator.paxTypeAt(TRAVEL.minusYears(2).plusDays(1), TRAVEL)).isEqualTo(PaxType.INFANT);
    }

    @Test
    void exactlyTwelveOnTheTravelDateIsAnAdult() {
        assertThat(PaxTypeCalculator.paxTypeAt(TRAVEL.minusYears(12), TRAVEL)).isEqualTo(PaxType.ADULT);
    }

    @Test
    void oneDayShortOfTwelveIsStillAChild() {
        assertThat(PaxTypeCalculator.paxTypeAt(TRAVEL.minusYears(12).plusDays(1), TRAVEL)).isEqualTo(PaxType.CHILD);
    }

    /** The whole reason there is no stored age column: the answer changes with the travel date. */
    @Test
    void theSameChildIsAChildOnAnEarlierTripAndAnAdultOnALaterOne() {
        LocalDate twelfthBirthday = LocalDate.of(2026, 10, 1);
        LocalDate dob = twelfthBirthday.minusYears(12);

        assertThat(PaxTypeCalculator.paxTypeAt(dob, twelfthBirthday.minusDays(1))).isEqualTo(PaxType.CHILD);
        assertThat(PaxTypeCalculator.paxTypeAt(dob, twelfthBirthday)).isEqualTo(PaxType.ADULT);
    }

    @Test
    void aBirthdayInsideTheTripWindowIsFlaggedAsCrossingAFareBoundary() {
        LocalDate departure = LocalDate.of(2026, 9, 15);
        LocalDate returnDate = LocalDate.of(2026, 9, 25);
        LocalDate dob = LocalDate.of(2014, 9, 20);

        assertThat(PaxTypeCalculator.crossesPaxBoundary(dob, departure, returnDate)).isTrue();
    }

    @Test
    void aBirthdayOutsideTheTripWindowIsNotFlagged() {
        assertThat(PaxTypeCalculator.crossesPaxBoundary(
                LocalDate.of(2014, 3, 20), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 25))).isFalse();
    }

    @Test
    void aMissingDateOfBirthIsUnknownRatherThanADefaultFare() {
        assertThat(PaxTypeCalculator.paxTypeAt(null, TRAVEL)).isEqualTo(PaxType.UNKNOWN);
    }

    @Test
    void aLeadWithNoTravelDateYetIsUnknownRatherThanMeasuredAgainstToday() {
        assertThat(PaxTypeCalculator.paxTypeAt(LocalDate.of(1990, 1, 1), null)).isEqualTo(PaxType.UNKNOWN);
    }

    @Test
    void ageAtReturnsNullWhenEitherInputIsMissing() {
        assertThat(PaxTypeCalculator.ageAt(null, TRAVEL)).isNull();
        assertThat(PaxTypeCalculator.ageAt(LocalDate.of(1990, 1, 1), null)).isNull();
    }

    /** A date of birth after travel is a typo; clamping to 0 beats classifying them as an infant. */
    @Test
    void aDateOfBirthAfterTheTravelDateClampsToZeroRatherThanGoingNegative() {
        assertThat(PaxTypeCalculator.ageAt(TRAVEL.plusYears(1), TRAVEL)).isZero();
    }
}
