package com.voyra.crm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** What an agency's discount does to one fare; the intermediate stays at full precision and the result is rounded HALF_UP to 2 dp. */
public final class PricingMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PricingMath() {
    }

    /** The fare the agency's customer sees: gross less the discount percentage of gross. */
    public static BigDecimal displayed(BigDecimal gross, BigDecimal discountPct) {
        return gross.subtract(gross.multiply(discountPct).divide(HUNDRED)).setScale(2, RoundingMode.HALF_UP);
    }

    /** voyraNet = voyraEarned - (gross - displayed) - agencyCommission; only the statement import still uses it (removed with it). */
    public static BigDecimal voyraNetOf(BigDecimal voyraEarned, BigDecimal gross, BigDecimal displayed, BigDecimal agencyCommission) {
        return voyraEarned.subtract(gross.subtract(displayed)).subtract(agencyCommission).setScale(2, RoundingMode.HALF_UP);
    }
}
