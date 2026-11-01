package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PricingMathTest {

    @Test
    void displayedTakesTheDiscountOffGross() {
        assertThat(PricingMath.displayed(new BigDecimal("5000"), new BigDecimal("10"))).isEqualByComparingTo("4500.00");
        assertThat(PricingMath.displayed(new BigDecimal("999.99"), new BigDecimal("3.333"))).isEqualByComparingTo("966.66");
        assertThat(PricingMath.displayed(new BigDecimal("5000"), new BigDecimal("10")).scale()).isEqualTo(2);
    }
}
