package com.voyra.crm.models.booking;

/**
 * What a reviewed hotel room option asks for at booking (Tripjack v3 {@code option.compliance}): the lead guest's PAN,
 * every guest's passport number, and Voyra's GST details (a RESELLER or PASSTHROUGH rate).
 */
public record HotelCompliance(boolean panRequired, boolean passportRequired, boolean gstRequired) {

    public static final HotelCompliance NONE = new HotelCompliance(false, false, false);
}
