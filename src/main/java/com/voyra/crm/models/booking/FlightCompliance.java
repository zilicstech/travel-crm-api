package com.voyra.crm.models.booking;

/**
 * What a reviewed flight fare asks for at booking (Tripjack Air Review {@code conditions}): seat map applicability and
 * mandatory seat selection ({@code isa}, {@code ifsm || fsc.issi}, with {@code freeSeatOnlyMandatory} when only
 * {@code fsc.issi} is set so unbundled Saver fares without ₹0 seats stay optional), traveller ID ({@code dc.idm}),
 * PAN ({@code ipa}), passport and expiry ({@code pcs.pm}, {@code pcs.pped}), adult date of birth ({@code dob.adobr}),
 * emergency contact ({@code iecr}), and mandatory GST ({@code gst.igm}).
 */
public record FlightCompliance(
        boolean seatApplicable,
        boolean seatMandatory,
        boolean freeSeatOnlyMandatory,
        boolean documentIdRequired,
        boolean panRequired,
        boolean passportRequired,
        boolean passportExpiryRequired,
        boolean adultDobRequired,
        boolean emergencyContactRequired,
        boolean gstMandatory
) {

    public FlightCompliance(boolean seatApplicable, boolean seatMandatory, boolean documentIdRequired,
                            boolean panRequired, boolean passportRequired, boolean passportExpiryRequired,
                            boolean adultDobRequired, boolean emergencyContactRequired, boolean gstMandatory) {
        this(seatApplicable, seatMandatory, false, documentIdRequired, panRequired, passportRequired,
                passportExpiryRequired, adultDobRequired, emergencyContactRequired, gstMandatory);
    }

    public static final FlightCompliance NONE = new FlightCompliance(
            false, false, false, false, false, false, false, false, false, false);
}
