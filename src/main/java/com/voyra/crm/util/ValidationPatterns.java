package com.voyra.crm.util;

/** Regexes (and the cross-field rules built on them) shared by request DTOs and services, so a field means the same thing on every form. */
public final class ValidationPatterns {

    private ValidationPatterns() {
    }

    /** Optional phone: empty (clears it), or 7-20 of digits, spaces, +, - and parentheses. */
    public static final String PHONE = "^$|^[0-9+()\\- ]{7,20}$";
    public static final String PHONE_MESSAGE = "Enter a valid phone number (7-20 digits, spaces, +, - or brackets)";

    /** A traveller's first or last name as airlines take it: Latin letters, spaces and . - ' only, starting with a letter. */
    public static final String PERSON_NAME = "[A-Za-z][A-Za-z .'-]*";
    public static final String PERSON_NAME_MESSAGE = "Use letters, spaces and . - ' only, starting with a letter";

    /** Indian GSTIN format only (2-digit state, PAN, entity digit, Z, check char) - the checksum itself is not verified. Case-insensitive; services store it upper-case. */
    public static final String GSTIN = "^$|(?i)^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    public static final String GSTIN_MESSAGE = "Enter a valid 15-character GSTIN";

    /** IFSC: 4 bank letters, a zero, 6 branch characters. */
    public static final String IFSC = "^$|(?i)^[A-Z]{4}0[A-Z0-9]{6}$";
    public static final String IFSC_MESSAGE = "Enter a valid 11-character IFSC";

    /** A GSTIN's first two digits are its state code; refuses a pair that disagrees (either blank passes). */
    public static void requireGstinMatchesState(String gstin, String stateCode) {
        if (gstin == null || gstin.isBlank() || stateCode == null || stateCode.isBlank()) {
            return;
        }
        String gstinState = gstin.trim().substring(0, Math.min(2, gstin.trim().length()));
        if (!gstinState.equals(stateCode.trim())) {
            throw new IllegalArgumentException("This GSTIN is registered in state " + gstinState + ", but the GST state is "
                    + stateCode.trim() + " - the first two digits of a GSTIN are its state code");
        }
    }

    /** A B2C identifier is the household's phone number, so it must look like one. */
    public static void requirePhone(String value) {
        if (value != null && !value.matches(PHONE)) {
            throw new IllegalArgumentException(PHONE_MESSAGE);
        }
    }
}
