package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void customerGetsBackWhatTheyPaidLessWhatTheAgencyKeeps() {
        assertThat(CancellationMath.customerRefundDue(d("10000"), d("2000"), d("500"))).isEqualByComparingTo("7500");
    }

    @Test
    void refundNeverGoesBelowZeroWhenTheCustomerPaidLessThanTheCharges() {
        assertThat(CancellationMath.customerRefundDue(d("1000"), d("2000"), d("0"))).isEqualByComparingTo("0");
        assertThat(CancellationMath.customerRefundDue(BigDecimal.ZERO, d("100"), d("100"))).isEqualByComparingTo("0");
    }

    @Test
    void missingAmountsCountAsZero() {
        assertThat(CancellationMath.customerRefundDue(d("5000"), null, null)).isEqualByComparingTo("5000");
        assertThat(CancellationMath.customerRefundDue(null, null, null)).isEqualByComparingTo("0");
        assertThat(CancellationMath.supplierRefundExpected(null, d("10"))).isEqualByComparingTo("0");
    }

    @Test
    void supplierOwesBackWhatWasPaidLessItsCharge() {
        assertThat(CancellationMath.supplierRefundExpected(d("8000"), d("2000"))).isEqualByComparingTo("6000");
        assertThat(CancellationMath.supplierRefundExpected(d("1500"), d("2000"))).isEqualByComparingTo("0");
    }
}
