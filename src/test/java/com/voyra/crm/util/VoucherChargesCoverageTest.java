package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoucherChargesCoverageTest {

    private static final java.util.function.Function<BigDecimal, String> MONEY = InvoicePdfRenderer::money;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void describeNeedsChargesAnAmountAndRoomForThem() {
        assertThat(VoucherCharges.describe(null, BigDecimal.TEN, MONEY)).isNull();
        assertThat(VoucherCharges.describe(Map.of(), BigDecimal.TEN, MONEY)).isNull();
        assertThat(VoucherCharges.describe(Map.of("basic", bd("5")), null, MONEY)).isNull();
        assertThat(VoucherCharges.describe(Map.of("basic", bd("15")), BigDecimal.TEN, MONEY)).isNull();
        assertThat(VoucherCharges.describe(Map.of("basic", bd("10"), "yq", bd("0")), BigDecimal.TEN, MONEY))
                .isEqualTo("Basic 10.00 · YQ Tax 0.00");
    }

    @Test
    void cleanDropsZerosKeepsOrderAndRefusesUnknownOrNegativeCharges() {
        Map<String, BigDecimal> in = new HashMap<>();
        in.put("yq", bd("1.005"));
        in.put("basic", bd("100"));
        in.put("k3", BigDecimal.ZERO);
        in.put("oc", null);

        assertThat(VoucherCharges.clean(null)).isNull();
        assertThat(VoucherCharges.clean(Map.of())).isNull();
        assertThat(VoucherCharges.clean(Map.of("basic", BigDecimal.ZERO))).isNull();
        assertThat(VoucherCharges.clean(in)).containsExactly(Map.entry("basic", bd("100.00")), Map.entry("yq", bd("1.01")));
        assertThatThrownBy(() -> VoucherCharges.clean(Map.of("tip", BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown charge tip");
        assertThatThrownBy(() -> VoucherCharges.clean(Map.of("basic", bd("-1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A charge can't be negative");
    }

    @Test
    void sameComparesKeysAndValuesWhateverTheScale() {
        assertThat(VoucherCharges.same(null, null)).isTrue();
        assertThat(VoucherCharges.same(Map.of("basic", bd("1.0")), Map.of("basic", bd("1.00")))).isTrue();
        assertThat(VoucherCharges.same(Map.of("basic", bd("1")), Map.of("basic", bd("2")))).isFalse();
        assertThat(VoucherCharges.same(Map.of("basic", bd("1")), null)).isFalse();
        assertThat(VoucherCharges.same(Map.of("basic", bd("1")), Map.of("yq", bd("1")))).isFalse();
    }

    @Test
    void theTotalOfNoChargesIsZero() {
        assertThat(VoucherCharges.total(null)).isEqualByComparingTo("0");
    }

    @Test
    void thePurchasePriceIsTheChargesTotalAndAnyPriceSentMustAgree() {
        Map<String, BigDecimal> c = Map.of("basic", bd("90"), "yq", bd("10"));

        assertThat(VoucherCharges.purchasePrice(c, null, null, false)).isEqualByComparingTo("100");
        assertThat(VoucherCharges.purchasePrice(c, null, bd("999"), false)).isEqualByComparingTo("100");
        assertThat(VoucherCharges.purchasePrice(c, null, bd("100.00"), true)).isEqualByComparingTo("100");
        assertThat(VoucherCharges.purchasePrice(c, bd("100"), null, false)).isEqualByComparingTo("100");
        assertThatThrownBy(() -> VoucherCharges.purchasePrice(c, bd("99"), null, false))
                .hasMessage("The purchase price (99) isn't the total of the charges (100)");
        assertThatThrownBy(() -> VoucherCharges.purchasePrice(c, null, bd("98"), true))
                .hasMessage("The purchase price (98) isn't the total of the charges (100)");
        assertThatThrownBy(() -> VoucherCharges.purchasePrice(null, null, null, true)).hasMessage("Enter the fare charges");
    }

    @Test
    void toBaseConvertsAtTheRateAndPutsTheRoundingOnTheLargest() {
        Map<String, BigDecimal> c = new LinkedHashMap<>();
        c.put("yq", bd("1.11"));
        c.put("basic", bd("3.33"));
        c.put("k3", bd("2.22"));

        assertThat(VoucherCharges.toBase(null, BigDecimal.ONE, BigDecimal.ONE)).isNull();
        assertThat(VoucherCharges.toBase(c, null, null)).containsExactly(
                Map.entry("yq", bd("1.11")), Map.entry("basic", bd("3.33")), Map.entry("k3", bd("2.22")));
        assertThat(VoucherCharges.toBase(c, bd("3"), bd("20.00"))).containsExactly(
                Map.entry("yq", bd("3.33")), Map.entry("basic", bd("10.01")), Map.entry("k3", bd("6.66")));
        assertThat(VoucherCharges.toBase(Map.of(), bd("3"), bd("1"))).isEmpty();
    }

    @Test
    void noCommissionWithoutAModeOrAValue() {
        assertThat(VoucherCharges.commission(null, bd("100"), null, bd("5"), null)).isSameAs(VoucherCharges.Commission.NONE);
        assertThat(VoucherCharges.commission(null, bd("100"), MarkupMode.FLAT, null, null)).isSameAs(VoucherCharges.Commission.NONE);
        assertThat(VoucherCharges.commission(null, bd("100"), MarkupMode.FLAT, BigDecimal.ZERO, null)).isSameAs(VoucherCharges.Commission.NONE);
    }

    @Test
    void aPercentCommissionIsOfTheBasicFareWithTdsAtTheGivenRate() {
        VoucherCharges.Commission c = VoucherCharges.commission(Map.of("basic", bd("1000")), bd("1200"), MarkupMode.PERCENT,
                bd("5"), bd("10"));

        assertThat(c.amount()).isEqualByComparingTo("50.00");
        assertThat(c.tds()).isEqualByComparingTo("5.00");
        assertThat(c.tdsRate()).isEqualByComparingTo("10");
    }

    @Test
    void commissionRulesRefuseBadInputs() {
        assertThatThrownBy(() -> VoucherCharges.commission(null, bd("100"), MarkupMode.FLAT, bd("-1"), null))
                .hasMessage("Commission can't be negative");
        assertThatThrownBy(() -> VoucherCharges.commission(null, bd("100"), MarkupMode.PERCENT, bd("5"), null))
                .hasMessage("Commission as a % is of the Basic fare - enter the fare breakdown");
        assertThatThrownBy(() -> VoucherCharges.commission(Map.of("yq", bd("5")), bd("100"), MarkupMode.PERCENT, bd("5"), null))
                .hasMessage("Commission as a % is of the Basic fare - enter the fare breakdown");
        assertThatThrownBy(() -> VoucherCharges.commission(Map.of("basic", BigDecimal.ZERO), bd("100"), MarkupMode.PERCENT, bd("5"), null))
                .hasMessage("Commission as a % is of the Basic fare - enter the fare breakdown");
        assertThatThrownBy(() -> VoucherCharges.commission(null, null, MarkupMode.FLAT, bd("5"), null))
                .hasMessage("The commission is more than the supplier's bill");
        assertThatThrownBy(() -> VoucherCharges.commission(null, bd("100"), MarkupMode.FLAT, bd("5"), bd("-1")))
                .hasMessage("TDS on commission is a % between 0 and 100");
        assertThatThrownBy(() -> VoucherCharges.commission(null, bd("100"), MarkupMode.FLAT, bd("5"), bd("101")))
                .hasMessage("TDS on commission is a % between 0 and 100");
    }
}
