package com.voyra.crm.enums;

/**
 * Why an agency asks Voyra to cancel, grouped as Tripjack groups them (owner ruling 2026-11-18). The timing hints
 * (void same day, no-show after 24 h) are for the agency to read; the platform admin reviews every request, so the
 * server does not enforce them. Refunds are always subject to the airline's or hotel's confirmation.
 */
public enum CancelReason {
    TRAVEL_PLAN_CANCELLED(Category.NORMAL, PricingProduct.FLIGHT, "Travel plan is cancelled"),
    ALREADY_CANCELLED_WITH_AIRLINE(Category.NORMAL, PricingProduct.FLIGHT, "Already cancelled with the airline"),
    FLIGHT_CANCELLED_BY_AIRLINE(Category.FULL_REFUND, PricingProduct.FLIGHT, "Flight cancelled by the airline"),
    FLIGHT_TIMING_CHANGED(Category.FULL_REFUND, PricingProduct.FLIGHT, "Airline changed the flight timing"),
    DGCA_FULL_REFUND(Category.FULL_REFUND, PricingProduct.FLIGHT, "Full refund under DGCA airline policy"),
    AIRLINE_CONFIRMED_FULL_REFUND(Category.FULL_REFUND, PricingProduct.FLIGHT, "Airline confirmed a full refund"),
    VOID_SAME_DAY(Category.VOID, PricingProduct.FLIGHT, "Void ticket (same day)"),
    NO_SHOW(Category.NO_SHOW, PricingProduct.FLIGHT, "Passenger no-show"),
    MEDICALLY_UNFIT(Category.OTHER, PricingProduct.FLIGHT, "Passenger medically unfit to travel"),
    BEREAVEMENT(Category.OTHER, PricingProduct.FLIGHT, "Personal loss or bereavement"),
    AIRLINE_EMPOWERMENT(Category.OTHER, PricingProduct.FLIGHT, "Refund under airline empowerment policy"),
    HOTEL_CHANGE_OF_PLANS(Category.NORMAL, PricingProduct.HOTEL, "Change of plans"),
    HOTEL_ASKED_TO_CANCEL(Category.FULL_REFUND, PricingProduct.HOTEL, "Hotel asked to cancel or is unavailable"),
    HOTEL_DUPLICATE_BOOKING(Category.NORMAL, PricingProduct.HOTEL, "Duplicate booking"),
    HOTEL_MEDICAL(Category.OTHER, PricingProduct.HOTEL, "Medical emergency"),
    HOTEL_OTHER(Category.OTHER, PricingProduct.HOTEL, "Other reason");

    public enum Category { NORMAL, FULL_REFUND, VOID, NO_SHOW, OTHER }

    private final Category category;
    private final PricingProduct product;
    private final String label;

    CancelReason(Category category, PricingProduct product, String label) {
        this.category = category;
        this.product = product;
        this.label = label;
    }

    /** How emails name it (the screens keep their own wording). */
    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public PricingProduct product() {
        return product;
    }

    /** A reason in the Other group needs the agency's own words. */
    public boolean needsRemarks() {
        return category == Category.OTHER;
    }
}
