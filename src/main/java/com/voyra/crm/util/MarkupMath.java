package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The selling price from a cost and a markup - the server-side twin of the frontend's {@code withMarkup}:
 *  rounded to the whole rupee, since a customer-facing price with paise reads as a pricing bug. */
public final class MarkupMath {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private MarkupMath() {
    }

    public static BigDecimal sellingPrice(BigDecimal cost, MarkupMode mode, BigDecimal value) {
        BigDecimal c = cost != null ? cost : BigDecimal.ZERO;
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        BigDecimal price = mode == MarkupMode.FLAT ? c.add(v) : c.add(c.multiply(v).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        return price.setScale(0, RoundingMode.HALF_UP);
    }
}
