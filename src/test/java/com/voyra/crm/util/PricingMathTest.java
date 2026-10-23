package com.voyra.crm.util;

import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.models.ReferenceFare;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingMathTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static final List<ReferenceFare> BASKET = List.of(
            new ReferenceFare("Domestic LCC", bd("5000"), bd("3500")),
            new ReferenceFare("Domestic FSC", bd("9000"), bd("6000")),
            new ReferenceFare("International", bd("45000"), bd("22000")));

    @Test
    void grossDiscountMatchesTheOwnersExample() {
        assertThat(PricingMath.displayed(bd("5000"), bd("3500"), bd("10"), DiscountBase.GROSS)).isEqualByComparingTo("4500.00");
        assertThat(PricingMath.displayed(bd("5000"), bd("3500"), bd("10"), DiscountBase.GROSS).scale()).isEqualTo(2);
    }

    @Test
    void baseDiscountLeavesTaxesAlone() {
        assertThat(PricingMath.displayed(bd("5000"), bd("3500"), bd("10"), DiscountBase.BASE)).isEqualByComparingTo("4650.00");
    }

    @Test
    void voyraNetBleedsOnTaxHeavyFares() {
        // 1100 earned - 1350 discount - 880 agency commission
        assertThat(PricingMath.voyraNet(bd("45000"), bd("22000"), bd("3"), bd("4"), bd("5"), DiscountBase.GROSS))
                .isEqualByComparingTo("-1130.00");
    }

    @Test
    void commissionIsCappedAtWhatVoyraEarned() {
        // c 8 > e 5: the agency term is 22000 x 5% = 1100, not 1760
        assertThat(PricingMath.voyraNet(bd("45000"), bd("22000"), bd("3"), bd("8"), bd("5"), DiscountBase.GROSS))
                .isEqualByComparingTo("-1350.00");
    }

    @Test
    void commissionAboveEarnedIsNotCappedWhenAllowed() {
        // 1100 - 1350 - 1760
        assertThat(PricingMath.voyraNet(bd("45000"), bd("22000"), bd("3"), bd("8"), bd("5"), DiscountBase.GROSS, true))
                .isEqualByComparingTo("-2010.00");
        assertThat(PricingMath.worstCaseNetPer10k(BASKET, bd("0"), bd("8"), bd("5"), DiscountBase.GROSS, true))
                .isEqualByComparingTo("-210.00");
        assertThat(PricingMath.worstCaseNetPer10k(BASKET, bd("0"), bd("8"), bd("5"), DiscountBase.GROSS))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void worstCaseIsTheMinimumAcrossTheBasket() {
        // per 10k: LCC -230.00, FSC -233.33, International -251.11
        assertThat(PricingMath.worstCaseNetPer10k(BASKET, bd("3"), bd("4"), bd("5"), DiscountBase.GROSS))
                .isEqualByComparingTo("-251.11");
        // no discount, no commission: LCC 350.00, FSC 333.33, International 244.44
        assertThat(PricingMath.worstCaseNetPer10k(BASKET, bd("0"), bd("0"), bd("5"), DiscountBase.GROSS))
                .isEqualByComparingTo("244.44");
    }

    @Test
    void worstCaseRefusesAnEmptyBasket() {
        assertThatThrownBy(() -> PricingMath.worstCaseNetPer10k(List.of(), bd("3"), bd("4"), bd("5"), DiscountBase.GROSS))
                .isInstanceOf(IllegalStateException.class).hasMessage("Reference fare basket is empty");
    }
}
