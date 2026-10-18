package com.voyra.crm.util;

/** Regexes shared by request DTOs' {@code @Pattern}s, so a field means the same thing on every form. */
public final class ValidationPatterns {

    private ValidationPatterns() {
    }

    /** Optional phone: empty (clears it), or 7-20 of digits, spaces, +, - and parentheses. */
    public static final String PHONE = "^$|^[0-9+()\\- ]{7,20}$";
    public static final String PHONE_MESSAGE = "Enter a valid phone number (7-20 digits, spaces, +, - or brackets)";

    /** Indian GSTIN format only (2-digit state, PAN, entity digit, Z, check char) - the checksum itself is not verified. Case-insensitive; services store it upper-case. */
    public static final String GSTIN = "^$|(?i)^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    public static final String GSTIN_MESSAGE = "Enter a valid 15-character GSTIN";

    /** IFSC: 4 bank letters, a zero, 6 branch characters. */
    public static final String IFSC = "^$|(?i)^[A-Z]{4}0[A-Z0-9]{6}$";
    public static final String IFSC_MESSAGE = "Enter a valid 11-character IFSC";
}
