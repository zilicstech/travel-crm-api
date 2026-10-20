package com.voyra.crm.util;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.enums.MarkupMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxMathTest {

    @Test
    void theBaseCurrencyIsTakenAtOneWhateverRateIsSent() {
        FxMath.Converted c = FxMath.toBase("inr", new BigDecimal("4000"), new BigDecimal("99"), "INR");
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.rate()).isEqualByComparingTo("1");
        assertThat(c.base()).isEqualByComparingTo("4000.00");
        assertThat(FxMath.toBase(null, new BigDecimal("10"), null, "AED").currency()).isEqualTo("AED");
    }

    @Test
    void aForeignAmountIsConvertedAtTheRateAndRoundedToTwoPlaces() {
        assertThat(FxMath.toBase("AED", new BigDecimal("1000"), new BigDecimal("22.85"), "INR").base()).isEqualByComparingTo("22850.00");
        assertThat(FxMath.toBase("USD", new BigDecimal("333.33"), new BigDecimal("83.333333"), "INR").base())
                .isEqualByComparingTo("27777.50");
    }

    @Test
    void aForeignAmountNeedsARateAndARealCurrency() {
        assertThatThrownBy(() -> FxMath.toBase("AED", BigDecimal.TEN, null, "INR")).hasMessageContaining("Enter the AED rate");
        assertThatThrownBy(() -> FxMath.toBase("AED", BigDecimal.TEN, BigDecimal.ZERO, "INR")).hasMessageContaining("Enter the AED rate");
        assertThatThrownBy(() -> FxMath.toBase("XYZ1", BigDecimal.TEN, BigDecimal.ONE, "INR")).hasMessageContaining("Unknown currency code");
    }

    @Test
    void anOlderRequestWithOnlyBaseFiguresIsLeftAsItIs() {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setNetCost(new BigDecimal("4000"));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("10"));

        FxMath.Pricing p = FxMath.resolve(r, "INR");

        assertThat(p.netCost()).isEqualByComparingTo("4000");
        assertThat(p.markupValue()).isEqualByComparingTo("10");
        assertThat(p.purchase()).isNull();
        assertThat(p.markup()).isNull();
    }

    @Test
    void aForeignPurchaseAndFlatMarkupBecomeBaseFigures() {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setPurchaseCurrency("AED");
        r.setPurchasePrice(new BigDecimal("1000"));
        r.setPurchaseRate(new BigDecimal("22.85"));
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupCurrency("USD");
        r.setMarkupInput(new BigDecimal("10"));
        r.setMarkupRate(new BigDecimal("83.5"));

        FxMath.Pricing p = FxMath.resolve(r, "INR");

        assertThat(p.netCost()).isEqualByComparingTo("22850.00");
        assertThat(p.markupValue()).isEqualByComparingTo("835.00");
        assertThat(MarkupMath.sellingPrice(p.netCost(), MarkupMode.FLAT, p.markupValue())).isEqualByComparingTo("23685");
    }

    @Test
    void aPercentMarkupIgnoresAnyFlatMarkupFields() {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setNetCost(new BigDecimal("1000"));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("5"));
        r.setMarkupCurrency("AED");
        r.setMarkupInput(new BigDecimal("100"));

        assertThat(FxMath.resolve(r, "INR").markupValue()).isEqualByComparingTo("5");
    }
}
