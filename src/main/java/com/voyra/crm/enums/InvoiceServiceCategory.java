package com.voyra.crm.enums;

/**
 * The service a customer invoice is <em>for</em>, and therefore the document's identity: it
 * selects the number series, the printed title, and the print template's columns.
 *
 * <p>This replaces "invoice with N arbitrary lines" with "invoice of a category, bound to one
 * booking" - see {@code ACCOUNTING_REDESIGN_SPEC.md} §5.1. The operator picks a booking; the
 * booking's own fields become the printed body. Nothing about the body is hand-authored, so
 * there is no description/quantity/unit-price step anywhere in the UI.
 *
 * <p>The set is taken from the document types the client's outgoing system actually issues.
 * {@link #MISCELLANEOUS} is not a fallback afterthought - in that system it is the
 * second-highest-volume document of the year, so it is a first-class category.
 *
 * <p>{@link #AIR_INTERNATIONAL} / {@link #AIR_DOMESTIC} split {@link BookingType#FLIGHT};
 * {@link #RAIL} and {@link #MISCELLANEOUS} have no {@link BookingType} counterpart yet (see
 * ACCOUNTING_REDESIGN_SPEC.md gap 8) and can only be reached today by an explicit override at
 * draft time, never by {@link #forBooking}.
 */
public enum InvoiceServiceCategory {

    AIR_INTERNATIONAL("ITI", "International Air Ticket Invoice", true, true),
    AIR_DOMESTIC("DTI", "Domestic Air Ticket Invoice", true, true),
    HOTEL("HTL", "Hotel Invoice", true, false),
    RAIL("RLI", "Railway Ticket Invoice", true, true),
    TRANSPORT("TRN", "Transport Invoice", false, true),
    VISA("VSA", "Visa Invoice", true, false),
    PACKAGE("PKG", "Package Invoice", true, false),
    MISCELLANEOUS("MSC", "Miscellaneous Invoice", false, false);

    private final String numberPrefix;
    private final String documentTitle;
    private final boolean passengerWise;
    private final boolean sectorWise;

    InvoiceServiceCategory(String numberPrefix, String documentTitle,
                           boolean passengerWise, boolean sectorWise) {
        this.numberPrefix = numberPrefix;
        this.documentTitle = documentTitle;
        this.passengerWise = passengerWise;
        this.sectorWise = sectorWise;
    }

    /**
     * Series prefix, e.g. {@code ITI} - the printed number is {@code ITI00001323}. Prefixes match
     * the outgoing system's so the client's numbering reads continuously across the cutover; the
     * starting counter is set per tenant when its series row is seeded.
     */
    public String numberPrefix() {
        return numberPrefix;
    }

    /** Printed as the document heading, e.g. "International Air Ticket Invoice". */
    public String documentTitle() {
        return documentTitle;
    }

    /** Whether the printed body lists one row per traveller. Transport and misc bill the booking as a whole. */
    public boolean isPassengerWise() {
        return passengerWise;
    }

    /** Whether the printed body carries itinerary legs (sector/flight/travel date) under each row. */
    public boolean isSectorWise() {
        return sectorWise;
    }

    /**
     * The category a booking falls in by default. {@code international} is a property of the
     * booking itself ({@code Booking.internationalTrip}), set by whoever books it - there is no
     * airport-code lookup here. {@link #RAIL} and {@link #MISCELLANEOUS} have no
     * {@link BookingType} to derive from and are never returned by this method; a draft that
     * needs either is created with an explicit category instead.
     */
    public static InvoiceServiceCategory forBooking(BookingType bookingType, boolean international) {
        if (bookingType == null) {
            return MISCELLANEOUS;
        }
        return switch (bookingType) {
            case FLIGHT -> international ? AIR_INTERNATIONAL : AIR_DOMESTIC;
            case HOTEL -> HOTEL;
            case VISA -> VISA;
            case TRANSFER -> TRANSPORT;
            case PACKAGE -> PACKAGE;
        };
    }
}
