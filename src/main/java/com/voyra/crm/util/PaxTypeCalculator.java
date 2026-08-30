package com.voyra.crm.util;

import com.voyra.crm.enums.PaxType;

import java.time.LocalDate;
import java.time.Period;

/**
 * Derives airline passenger class from date of birth against a travel date.
 *
 * <p>This exists because {@code member} deliberately has no age column. Age is only meaningful
 * relative to a date, and the date that matters commercially is departure, not today. A child
 * who is 11 when the enquiry is taken and 12 when the flight leaves is an adult fare; storing
 * either an age or a pax type would quietly produce a wrong quote the moment the trip slips.
 *
 * <p>Boundaries are the industry-standard ones: infant under 2, child 2 to 11 inclusive, adult
 * 12 and over, all measured on the departure date.
 *
 * <p>{@link PaxType#UNKNOWN} is returned when either input is null. That is a normal state,
 * not an error - the traveller manifest hardens progressively, so a lead can legitimately have
 * a named traveller with no date of birth yet, or no travel dates yet. Callers that need
 * certainty must check for UNKNOWN rather than assume a default.
 */
public final class PaxTypeCalculator {

    private static final int CHILD_MIN_AGE = 2;
    private static final int ADULT_MIN_AGE = 12;

    private PaxTypeCalculator() {
    }

    /**
     * @param dob        date of birth, may be null
     * @param travelDate the departure date the fare is being classified for, may be null
     * @return the pax class at {@code travelDate}, or UNKNOWN if either input is missing
     */
    public static PaxType paxTypeAt(LocalDate dob, LocalDate travelDate) {
        Integer age = ageAt(dob, travelDate);
        if (age == null) {
            return PaxType.UNKNOWN;
        }
        if (age < CHILD_MIN_AGE) {
            return PaxType.INFANT;
        }
        if (age < ADULT_MIN_AGE) {
            return PaxType.CHILD;
        }
        return PaxType.ADULT;
    }

    /**
     * Completed years between {@code dob} and {@code travelDate}, or null if either is missing.
     *
     * <p>A date of birth after the travel date yields 0 rather than a negative age - an unborn
     * traveller is a data-entry mistake, and returning a negative number would classify them
     * as an infant by accident.
     */
    public static Integer ageAt(LocalDate dob, LocalDate travelDate) {
        if (dob == null || travelDate == null) {
            return null;
        }
        if (dob.isAfter(travelDate)) {
            return 0;
        }
        return Period.between(dob, travelDate).getYears();
    }

    /**
     * Whether a traveller changes fare class between departure and return.
     *
     * <p>Worth surfacing to the agent: a birthday inside the trip window means the outbound and
     * inbound legs are priced differently, and airlines will reject a booking that classes both
     * legs the same.
     */
    public static boolean crossesPaxBoundary(LocalDate dob, LocalDate departure, LocalDate returnDate) {
        if (dob == null || departure == null || returnDate == null) {
            return false;
        }
        return paxTypeAt(dob, departure) != paxTypeAt(dob, returnDate);
    }
}
