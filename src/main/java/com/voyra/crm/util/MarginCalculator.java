package com.voyra.crm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** margin% = (sellingPrice - netCost) / sellingPrice * 100 - the same formula the UI already used, now server-side. */
public final class MarginCalculator {

    private MarginCalculator() {
    }

    public static BigDecimal marginPercent(BigDecimal netCost, BigDecimal sellingPrice) {
        if (sellingPrice == null || sellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = netCost != null ? netCost : BigDecimal.ZERO;
        return sellingPrice.subtract(cost)
                .divide(sellingPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }
}
