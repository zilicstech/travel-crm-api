package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @Test
    void matchesTheSourceSystemsOwnSampleInvoice() {
        // ACCOUNTING_REDESIGN_SPEC.md §3 - the exact figure off the client's Demo Invoice.pdf.
        assertThat(AmountInWords.forAmount(new BigDecimal("5245.13"), "AED"))
                .isEqualTo("Five Thousand Two Hundred Forty Five AED & Thirteen Fills");
    }

    @Test
    void inrUsesLakhAndCrore() {
        assertThat(AmountInWords.forAmount(new BigDecimal("1234567.00"), "INR"))
                .isEqualTo("Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven Rupees");
        assertThat(AmountInWords.forAmount(new BigDecimal("120000000.00"), "INR"))
                .isEqualTo("Twelve Crore Rupees");
    }

    @Test
    void usdUsesThousandMillionBillion() {
        assertThat(AmountInWords.forAmount(new BigDecimal("2500000.00"), "USD"))
                .isEqualTo("Two Million Five Hundred Thousand US Dollars");
    }

    @Test
    void zeroAmountStillPrints() {
        assertThat(AmountInWords.forAmount(BigDecimal.ZERO, "INR")).isEqualTo("Zero Rupees");
    }

    @Test
    void unlistedCurrencyFallsBackToTheRawCodeRatherThanFailing() {
        assertThat(AmountInWords.forAmount(new BigDecimal("100.00"), "XYZ"))
                .isEqualTo("One Hundred XYZ");
    }

    @Test
    void nullAmountIsTreatedAsZero() {
        assertThat(AmountInWords.forAmount(null, "INR")).isEqualTo("Zero Rupees");
    }
}
