package com.voyra.crm.util;

import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.models.ReferenceFare;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * What an agency's discount and commission do to one fare. Intermediates stay at full
 * precision; only the returned values are rounded HALF_UP to 2 dp.
 */
public final class PricingMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN_K = BigDecimal.valueOf(10000);

    private PricingMath() {
    }

    /** The fare the agency's customer sees after the discount. */
    public static BigDecimal displayed(BigDecimal gross, BigDecimal base, BigDecimal discountPct, DiscountBase db) {
        return round(rawDisplayed(gross, base, discountPct, db));
    }

    /** Voyra's net on one fare: earned - discount given away - agency commission (capped at earned). */
    public static BigDecimal voyraNet(BigDecimal gross, BigDecimal base, BigDecimal discountPct, BigDecimal commissionPct,
                                      BigDecimal voyraEarnedPct, DiscountBase db) {
        return voyraNet(gross, base, discountPct, commissionPct, voyraEarnedPct, db, false);
    }

    /** As above; {@code allowCommissionAboveEarned} drops the cap on the agency commission. */
    public static BigDecimal voyraNet(BigDecimal gross, BigDecimal base, BigDecimal discountPct, BigDecimal commissionPct,
                                      BigDecimal voyraEarnedPct, DiscountBase db, boolean allowCommissionAboveEarned) {
        return round(rawNet(gross, base, discountPct, commissionPct, voyraEarnedPct, db, allowCommissionAboveEarned));
    }

    /** Spec 5.1 on a booking's stamped figures: voyraNet = voyraEarned - (gross - displayed) - agencyCommission. */
    public static BigDecimal voyraNetOf(BigDecimal voyraEarned, BigDecimal gross, BigDecimal displayed, BigDecimal agencyCommission) {
        return round(voyraEarned.subtract(gross.subtract(displayed)).subtract(agencyCommission));
    }

    /** The lowest Voyra net per 10,000 of gross across the basket. */
    public static BigDecimal worstCaseNetPer10k(List<ReferenceFare> basket, BigDecimal discountPct, BigDecimal commissionPct,
                                                BigDecimal voyraEarnedPct, DiscountBase db) {
        return worstCaseNetPer10k(basket, discountPct, commissionPct, voyraEarnedPct, db, false);
    }

    public static BigDecimal worstCaseNetPer10k(List<ReferenceFare> basket, BigDecimal discountPct, BigDecimal commissionPct,
                                                BigDecimal voyraEarnedPct, DiscountBase db, boolean allowCommissionAboveEarned) {
        if (basket == null || basket.isEmpty()) {
            throw new IllegalStateException("Reference fare basket is empty");
        }
        BigDecimal worst = null;
        for (ReferenceFare f : basket) {
            if (f.gross().signum() <= 0) {
                throw new IllegalStateException("Reference fare " + f.label() + " has no gross fare");
            }
            BigDecimal per10k = rawNet(f.gross(), f.base(), discountPct, commissionPct, voyraEarnedPct, db, allowCommissionAboveEarned)
                    .multiply(TEN_K).divide(f.gross(), MathContext.DECIMAL128);
            worst = worst == null ? per10k : worst.min(per10k);
        }
        return round(worst);
    }

    private static BigDecimal rawDisplayed(BigDecimal gross, BigDecimal base, BigDecimal discountPct, DiscountBase db) {
        BigDecimal onto = db == DiscountBase.BASE ? base : gross;
        return gross.subtract(onto.multiply(discountPct).divide(HUNDRED));
    }

    private static BigDecimal rawNet(BigDecimal gross, BigDecimal base, BigDecimal discountPct, BigDecimal commissionPct,
                                     BigDecimal voyraEarnedPct, DiscountBase db, boolean allowCommissionAboveEarned) {
        BigDecimal earned = base.multiply(voyraEarnedPct).divide(HUNDRED);
        BigDecimal commission = base.multiply(commissionPct).divide(HUNDRED);
        if (!allowCommissionAboveEarned) {
            commission = commission.min(earned);
        }
        return earned.subtract(gross.subtract(rawDisplayed(gross, base, discountPct, db))).subtract(commission);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
