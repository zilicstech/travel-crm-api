package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarkupMathTest {

    @Test
    void percentAndFlatMarkupOnCost() {
        assertThat(MarkupMath.sellingPrice(new BigDecimal("4000"), MarkupMode.PERCENT, new BigDecimal("12.5"))).isEqualByComparingTo("4500.00");
        assertThat(MarkupMath.sellingPrice(new BigDecimal("4000"), MarkupMode.FLAT, new BigDecimal("750"))).isEqualByComparingTo("4750.00");
        assertThat(MarkupMath.sellingPrice(new BigDecimal("999.99"), MarkupMode.PERCENT, new BigDecimal("10"))).isEqualByComparingTo("1100");
        assertThat(MarkupMath.sellingPrice(null, null, null)).isEqualByComparingTo("0");
    }
}
