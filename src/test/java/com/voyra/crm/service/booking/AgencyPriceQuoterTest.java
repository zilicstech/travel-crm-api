package com.voyra.crm.service.booking;

import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.enums.PricingRuleKind;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyPriceQuoterTest {

    @Mock
    private AgencyPricingService pricingService;
    @Mock
    private PlatformSettingService settings;
    @InjectMocks
    private AgencyPriceQuoter quoter;

    private void rates(String discount, String commission, String tdsPct) {
        PricingRule d = PricingRule.builder().id("D1").kind(PricingRuleKind.DISCOUNT).product(PricingProduct.FLIGHT)
                .pct(new BigDecimal(discount)).version(1).build();
        PricingRule c = PricingRule.builder().id("C1").kind(PricingRuleKind.COMMISSION).product(PricingProduct.FLIGHT)
                .pct(new BigDecimal(commission)).version(2).build();
        when(pricingService.resolve("T", PricingProduct.FLIGHT)).thenReturn(Optional.of(new AgencyPricingService.EffectivePricing(d, c)));
        lenient().when(settings.tdsRate()).thenReturn(new BigDecimal(tdsPct));
    }

    private PriceQuote quote(String gross, String base) {
        return quoter.quote("T", PricingProduct.FLIGHT, new BigDecimal(gross), new BigDecimal(base));
    }

    @Test
    void displayedIsGrossLessDiscountOnGross() {
        rates("10", "0", "0");
        PriceQuote q = quote("5000", "4000");
        assertThat(q.displayed()).isEqualByComparingTo("4500.00");
        assertThat(q.pricingId()).isEqualTo("D:D1:v1|C:C1:v2");  // a rule edit (new version) re-prices at confirm
        assertThat(q.discountPct()).isEqualByComparingTo("10");
        assertThat(q.commissionPct()).isEqualByComparingTo("0");
    }

    @Test
    void commissionIsTheRulePercentageOfBaseWithNoCap() {
        rates("5", "8", "2");
        PriceQuote q = quote("12000", "10000");
        assertThat(q.displayed()).isEqualByComparingTo("11400.00");
        assertThat(q.agencyCommission()).isEqualByComparingTo("800.00");
        assertThat(q.tds()).isEqualByComparingTo("16.00");
        assertThat(q.agencyEarns()).isEqualByComparingTo("784.00");
    }

    @Test
    void noRulesResolvedMeansNotBookable() {
        when(pricingService.resolve("T", PricingProduct.FLIGHT)).thenReturn(Optional.empty());
        assertThat(quoter.quoterFor("T", PricingProduct.FLIGHT)).isEmpty();
        assertThatThrownBy(() -> quote("5000", "4000")).isInstanceOf(PricingNotConfiguredException.class);
    }
}
