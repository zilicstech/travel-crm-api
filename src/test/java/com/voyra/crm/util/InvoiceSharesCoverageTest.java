package com.voyra.crm.util;

import com.voyra.crm.entity.InvoiceLineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceSharesCoverageTest {

    private static InvoiceLineItem line(String booking, String taxable, String subtotal) {
        return InvoiceLineItem.builder().bookingId(booking).taxableValue(taxable == null ? null : new BigDecimal(taxable))
                .lineSubtotal(subtotal == null ? null : new BigDecimal(subtotal)).build();
    }

    @Test
    void withoutAPrimaryTheFirstLinesBookingLeadsAndMissingTaxableFallsBackToSubtotal() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of(null, List.of(
                line(null, "10", null), line("B2", null, "30"), line("B1", null, null), line("B1", "60", null)));

        assertThat(shares.keySet()).containsExactly("B2", "B1");
        assertThat(shares.get("B2").taxable()).isEqualByComparingTo("40");
        assertThat(shares.get("B1").taxable()).isEqualByComparingTo("60");
        assertThat(shares.get("B2").weight()).isEqualByComparingTo("0.4");
    }

    @Test
    void linesWithNoBookingAnywhereHaveNoShare() {
        assertThat(InvoiceShares.of(null, List.of(line(null, "10", null)))).isEmpty();
    }

    @Test
    void byBilledKeepsPriceWeightsWithoutAGrandTotalOrAFee() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("B1", List.of(line("B1", "100", null)));

        assertThat(InvoiceShares.byBilled(shares, null, Map.of())).isSameAs(shares);
        assertThat(InvoiceShares.byBilled(shares, BigDecimal.ZERO, Map.of())).isSameAs(shares);
        assertThat(InvoiceShares.byBilled(shares, new BigDecimal("118"), Map.of("B1", new BigDecimal("150")))).isSameAs(shares);
    }

    @Test
    void splittingNothingOrByNoSharesGivesNoParts() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("B1", List.of(line("B1", "100", null)));

        assertThat(InvoiceShares.split(null, shares)).isEmpty();
        assertThat(InvoiceShares.split(BigDecimal.TEN, Map.of())).isEmpty();
        assertThat(InvoiceShares.part(BigDecimal.TEN, Map.of(), "B1")).isEqualByComparingTo("0");
    }

    @Test
    void attributedSkipsNullAmountsAndSharesUnknownOrUnnamedOnes() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("B1", List.of(line("B1", "50", null), line("B2", "50", null)));
        List<Map.Entry<String, BigDecimal>> amounts = List.of(
                new AbstractMap.SimpleEntry<>("B1", null),
                new AbstractMap.SimpleEntry<>("B1", new BigDecimal("7")),
                new AbstractMap.SimpleEntry<>("B2", new BigDecimal("100")),
                new AbstractMap.SimpleEntry<>(null, new BigDecimal("10")),
                new AbstractMap.SimpleEntry<>("B9", new BigDecimal("20")));

        assertThat(InvoiceShares.attributed("B1", shares, amounts)).isEqualByComparingTo("22");
        assertThat(InvoiceShares.attributed("B2", shares, amounts)).isEqualByComparingTo("115");
    }
}
