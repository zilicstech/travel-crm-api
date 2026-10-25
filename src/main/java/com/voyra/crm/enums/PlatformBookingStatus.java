package com.voyra.crm.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PlatformBookingStatus {
    REVIEWING, HELD, BOOKING, UNKNOWN, TICKETED, CANCELLING, FAILED, CANCELLED, ABORTED;

    /** A wallet hold is open and the supplier outcome is not yet known. */
    public static final Set<PlatformBookingStatus> IN_FLIGHT = EnumSet.of(HELD, BOOKING, UNKNOWN);

    /** Still being settled with the supplier under the stamped credential: in flight, or a cancel submitted and unanswered. */
    public static final Set<PlatformBookingStatus> SUPPLIER_PENDING = EnumSet.of(HELD, BOOKING, UNKNOWN, CANCELLING);
}
