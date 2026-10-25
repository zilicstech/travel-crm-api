package com.voyra.crm.util;

import com.voyra.crm.enums.PaymentMode;
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

    /** 400 for a Voyra Wallet payment or refund from anyone but the system actor: only Voyra moves money through 1310. */
    public static void checkPaymentMode(PaymentMode mode) {
        if (mode == PaymentMode.VOYRA_WALLET && !SystemActor.isActive()) {
            throw new IllegalArgumentException("Voyra Wallet payments are recorded by Voyra only");
        }
    }
}
