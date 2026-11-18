package com.voyra.crm.models.booking;

/**
 * What a reviewed flight fare asks for at booking (Tripjack Air Review {@code conditions}): seat map applicability and
 * mandatory seat selection ({@code isa}, {@code ifsm || fsc.issi}), traveller ID ({@code dc.idm}), PAN ({@code ipa}),
 * passport and expiry ({@code pcs.pm}, {@code pcs.pped}), adult date of birth ({@code dob.adobr}), emergency contact
 * ({@code iecr}), and mandatory GST ({@code gst.igm}).
 */
public record FlightCompliance(
        boolean seatApplicable,
        boolean seatMandatory,
        boolean documentIdRequired,
        boolean panRequired,
        boolean passportRequired,
        boolean passportExpiryRequired,
        boolean adultDobRequired,
        boolean emergencyContactRequired,
        boolean gstMandatory
) {

    public static final FlightCompliance NONE = new FlightCompliance(
            false, false, false, false, false, false, false, false, false);
}
