package com.voyra.crm.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The two numbers a cancellation is about. Never negative: a customer who paid less than the
 *  agency keeps is owed nothing, not a negative refund. */
public final class CancellationMath {

    private CancellationMath() {
    }

    public static BigDecimal customerRefundDue(BigDecimal customerPaid, BigDecimal supplierCharge, BigDecimal agencyFee) {
        return floor(nz(customerPaid).subtract(nz(supplierCharge)).subtract(nz(agencyFee)));
    }

    public static BigDecimal supplierRefundExpected(BigDecimal paidToSupplier, BigDecimal supplierCharge) {
        return floor(nz(paidToSupplier).subtract(nz(supplierCharge)));
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal floor(BigDecimal v) {
        return v.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
