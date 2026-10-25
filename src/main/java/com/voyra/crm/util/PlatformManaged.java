package com.voyra.crm.util;

import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.security.SystemActor;

import java.util.Map;

/** The CRM mirror of a platform booking is read-only: only the platform, as the {@link SystemActor}, changes it (spec 6). */
public final class PlatformManaged {

    public static final String CODE = "MANAGED_FROM_PLATFORM";

    private PlatformManaged() {
    }

    /** 409 "Managed from Platform Bookings" when {@code mirrored}, unless the system actor is writing. */
    public static void check(boolean mirrored) {
        if (mirrored && !SystemActor.isActive()) {
            throw new BookingConflictException(CODE, "Managed from Platform Bookings", Map.of());
        }
    }
}
