package com.voyra.crm.util;

import com.voyra.crm.enums.MarkupMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoucherChargesTest {

    private static Map<String, BigDecimal> charges(String... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], new BigDecimal(kv[i + 1]));
        return m;
    }

    @Test
    void cleanKeepsKnownChargesInTicketOrderAndDropsZeros() {
        Map<String, BigDecimal> clean = VoucherCharges.clean(charges("k3", "410", "basic", "8200", "yq", "0"));
        assertThat(clean.keySet()).containsExactly("basic", "k3");
        assertThat(VoucherCharges.clean(charges("basic", "0"))).isNull();
        assertThatThrownBy(() -> VoucherCharges.clean(charges("fuel", "1"))).hasMessageContaining("Unknown charge");
        assertThatThrownBy(() -> VoucherCharges.clean(charges("basic", "-1"))).hasMessageContaining("negative");
    }

    @Test
    void theChargesAreThePurchasePriceAndADifferentOneIsRefused() {
        Map<String, BigDecimal> c = charges("basic", "8200", "yq", "1400", "k3", "410");
        assertThat(VoucherCharges.purchasePrice(c, null, null, true)).isEqualByComparingTo("10010");
        assertThat(VoucherCharges.purchasePrice(c, new BigDecimal("10010"), null, true)).isEqualByComparingTo("10010");
        assertThatThrownBy(() -> VoucherCharges.purchasePrice(c, null, new BigDecimal("9000"), true))
                .hasMessageContaining("isn't the total of the charges");
        // A base net cost beside a foreign purchase price is that price's base value, not a conflict.
        assertThat(VoucherCharges.purchasePrice(c, null, new BigDecimal("9000"), false)).isEqualByComparingTo("10010");
    }

    @Test
    void inBaseTheChargesStillAddUpToTheVoucherCost() {
        Map<String, BigDecimal> usd = charges("basic", "100.01", "yq", "20.01", "k3", "5.01");
        Map<String, BigDecimal> inr = VoucherCharges.toBase(usd, new BigDecimal("83.333"), new BigDecimal("10425.96"));
        assertThat(VoucherCharges.total(inr)).isEqualByComparingTo("10425.96");
        assertThat(inr.get("yq")).isEqualByComparingTo("1667.49");
    }

    @Test
    void commissionIsAPercentOfBasicOrFlatWithTheSuppliersTds() {
        Map<String, BigDecimal> c = charges("basic", "8200", "yq", "1400");
        VoucherCharges.Commission pct = VoucherCharges.commission(c, new BigDecimal("9600"), MarkupMode.PERCENT, new BigDecimal("5"), null);
        assertThat(pct.amount()).isEqualByComparingTo("410");
        assertThat(pct.tdsRate()).isEqualByComparingTo("2");
        assertThat(pct.tds()).isEqualByComparingTo("8.20");

        VoucherCharges.Commission flat = VoucherCharges.commission(null, new BigDecimal("9600"), MarkupMode.FLAT, new BigDecimal("300"), BigDecimal.ZERO);
        assertThat(flat.amount()).isEqualByComparingTo("300");
        assertThat(flat.tds()).isEqualByComparingTo("0");

        assertThat(VoucherCharges.commission(c, new BigDecimal("9600"), null, null, null)).isEqualTo(VoucherCharges.Commission.NONE);
    }

    @Test
    void aCommissionThatCantBeWorkedOutIsRefused() {
        assertThatThrownBy(() -> VoucherCharges.commission(null, new BigDecimal("9600"), MarkupMode.PERCENT, new BigDecimal("5"), null))
                .hasMessageContaining("of the Basic fare");
        assertThatThrownBy(() -> VoucherCharges.commission(null, new BigDecimal("100"), MarkupMode.FLAT, new BigDecimal("101"), null))
                .hasMessageContaining("more than the supplier's bill");
        assertThatThrownBy(() -> VoucherCharges.commission(null, new BigDecimal("100"), MarkupMode.FLAT, new BigDecimal("10"), new BigDecimal("120")))
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    void theInvoiceLineDescribesItsChargesAndMarkupOnlyWhenTheyFit() {
        Map<String, BigDecimal> c = charges("basic", "8200", "k3", "410");
        assertThat(VoucherCharges.describe(c, new BigDecimal("9610"), BigDecimal::toPlainString))
                .isEqualTo("Basic 8200 · K3 Tax 410 · Markup 1000");
        assertThat(VoucherCharges.describe(c, new BigDecimal("5000"), BigDecimal::toPlainString)).isNull();
    }
}
