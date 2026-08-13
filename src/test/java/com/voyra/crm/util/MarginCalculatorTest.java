package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarginCalculatorTest {

    @Test
    void computesMarginPercentFromNetCostAndSellingPrice() {
        BigDecimal result = MarginCalculator.marginPercent(new BigDecimal("8000"), new BigDecimal("10000"));
        assertThat(result).isEqualByComparingTo("20.0");
    }

    @Test
    void zeroSellingPriceReturnsZero() {
        BigDecimal result = MarginCalculator.marginPercent(new BigDecimal("5000"), BigDecimal.ZERO);
        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void nullSellingPriceReturnsZero() {
        BigDecimal result = MarginCalculator.marginPercent(new BigDecimal("5000"), null);
        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void nullNetCostTreatedAsZero() {
        BigDecimal result = MarginCalculator.marginPercent(null, new BigDecimal("10000"));
        assertThat(result).isEqualByComparingTo("100.0");
    }

    @Test
    void netCostAboveSellingPriceProducesNegativeMargin() {
        BigDecimal result = MarginCalculator.marginPercent(new BigDecimal("12000"), new BigDecimal("10000"));
        assertThat(result).isEqualByComparingTo("-20.0");
    }
}
