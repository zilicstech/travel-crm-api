package com.voyra.crm.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PlatformBookingStatus {
    REVIEWING, HELD, BOOKING, UNKNOWN, TICKETED, FAILED, CANCELLED, ABORTED;

    /** A wallet hold is open and the supplier outcome is not yet known. */
    public static final Set<PlatformBookingStatus> IN_FLIGHT = EnumSet.of(HELD, BOOKING, UNKNOWN);
}
