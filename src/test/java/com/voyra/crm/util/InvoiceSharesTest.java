package com.voyra.crm.util;

import com.voyra.crm.entity.InvoiceLineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceSharesTest {

    private static InvoiceLineItem line(String bookingId, String taxable) {
        return InvoiceLineItem.builder().bookingId(bookingId).taxableValue(new BigDecimal(taxable)).build();
    }

    @Test
    void anInvoiceOfOneBookingIsAllThatBookings() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("A", List.of(line("A", "700"), line(null, "300")));
        assertThat(shares).hasSize(1);
        assertThat(shares.get("A").weight()).isEqualByComparingTo("1");
        assertThat(InvoiceShares.part(new BigDecimal("1180.00"), shares, "A")).isEqualByComparingTo("1180.00");
    }

    @Test
    void sharesFollowEachBookingsTaxableAndAddUpExactly() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("A", List.of(line("A", "6000"), line("B", "4000")));
        Map<String, BigDecimal> parts = InvoiceShares.split(new BigDecimal("11800.00"), shares);
        assertThat(parts.get("A")).isEqualByComparingTo("7080.00");
        assertThat(parts.get("B")).isEqualByComparingTo("4720.00");

        Map<String, InvoiceShares.Share> thirds = InvoiceShares.of("A", List.of(line("A", "1"), line("B", "1"), line("C", "1")));
        Map<String, BigDecimal> split = InvoiceShares.split(new BigDecimal("100.00"), thirds);
        assertThat(split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(InvoiceShares.split(new BigDecimal("-100.00"), thirds).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-100.00");
    }

    @Test
    void aLineWithNoBookingCountsForTheFirstAndAZeroInvoiceIsAllTheFirsts() {
        Map<String, InvoiceShares.Share> shares = InvoiceShares.of("A", List.of(line("B", "500"), line(null, "500")));
        assertThat(shares.get("A").weight()).isEqualByComparingTo("0.5");
        assertThat(shares.get("B").weight()).isEqualByComparingTo("0.5");

        Map<String, InvoiceShares.Share> zero = InvoiceShares.of("A", List.of(line("A", "0"), line("B", "0")));
        assertThat(zero.get("A").weight()).isEqualByComparingTo("1");
        assertThat(zero.get("B").weight()).isEqualByComparingTo("0");
        assertThat(InvoiceShares.part(new BigDecimal("50"), zero, "B")).isEqualByComparingTo("0");
    }

    @Test
    void amountsNamingABookingCountForItAloneAndUnnamedOnesAreShared() {
        var shares = InvoiceShares.of("A", List.of(line("A", "7500"), line("B", "2500")));
        var amounts = List.of(java.util.Map.entry("A", new java.math.BigDecimal("1000")),
                java.util.Map.entry("B", new java.math.BigDecimal("300")),
                java.util.Map.entry("", new java.math.BigDecimal("400")));
        assertThat(InvoiceShares.attributed("A", shares, amounts)).isEqualByComparingTo("1300");
        assertThat(InvoiceShares.attributed("B", shares, amounts)).isEqualByComparingTo("400");
        // An invoice of one booking: everything is that booking's.
        assertThat(InvoiceShares.attributed("A", InvoiceShares.of("A", List.of(line("A", "10"))), amounts)).isEqualByComparingTo("1700");
    }
}
