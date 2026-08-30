package com.voyra.crm.enums;

/**
 * Airline passenger class, derived - never stored.
 *
 * <p>Pax type is a function of date of birth and the <em>travel</em> date, not of today.
 * A child who is 11 at enquiry and 12 at departure flies on an adult fare, so persisting a
 * computed age or pax type produces a wrong quote the moment the trip slips. Always resolve
 * through {@code util.PaxTypeCalculator}.
 *
 * <p>UNKNOWN covers both a missing date of birth and a lead with no travel date yet - the
 * manifest hardens progressively, so both are normal states rather than errors.
 */
public enum PaxType {
    ADULT, CHILD, INFANT, UNKNOWN
}
