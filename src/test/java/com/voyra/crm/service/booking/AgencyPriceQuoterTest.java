package com.voyra.crm.service.booking;

import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
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
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private void rates(String discount, String commission, boolean aboveEarned, String earnedPct, String tdsPct) {
        AgencyPricing row = AgencyPricing.builder().id("P1").tenantId("T").product(PricingProduct.FLIGHT)
                .discountPct(new BigDecimal(discount)).commissionPct(new BigDecimal(commission))
                .allowCommissionAboveEarned(aboveEarned).build();
        when(pricingService.resolve(eq("T"), eq(PricingProduct.FLIGHT), any(Instant.class))).thenReturn(Optional.of(row));
        lenient().when(settings.discountBase()).thenReturn(DiscountBase.GROSS);
        lenient().when(settings.commissionEstimatePct()).thenReturn(new BigDecimal(earnedPct));
        lenient().when(settings.tdsRate()).thenReturn(new BigDecimal(tdsPct));
    }

    private PriceQuote quote(String gross, String base) {
        return quoter.quote("T", PricingProduct.FLIGHT, new BigDecimal(gross), new BigDecimal(base));
    }

    @Test
    void displayedIsGrossLessDiscountOnGross() {
        rates("10", "0", false, "5", "0");
        PriceQuote q = quote("5000", "4000");
        assertThat(q.displayed()).isEqualByComparingTo("4500.00");
        assertThat(q.pricingId()).isEqualTo("P1");
        // earned 200 - discount 500 - commission 0
        assertThat(q.voyraNet()).isEqualByComparingTo("-300.00");
    }

    @Test
    void commissionCappedAtWhatVoyraEarns() {
        rates("0", "10", false, "5", "0");
        PriceQuote q = quote("5000", "4000");
        assertThat(q.voyraEarned()).isEqualByComparingTo("200.00");
        assertThat(q.agencyCommission()).isEqualByComparingTo("200.00");
        assertThat(q.agencyEarns()).isEqualByComparingTo("200.00");
    }

    @Test
    void commissionAboveEarnedWhenRowAllowsIt() {
        rates("0", "10", true, "5", "0");
        assertThat(quote("5000", "4000").agencyCommission()).isEqualByComparingTo("400.00");
    }

    @Test
    void tdsDeductedFromAgencyEarns() {
        rates("0", "2", false, "5", "5");
        PriceQuote q = quote("5000", "4000");
        assertThat(q.agencyCommission()).isEqualByComparingTo("80.00");
        assertThat(q.tds()).isEqualByComparingTo("4.00");
        assertThat(q.agencyEarns()).isEqualByComparingTo("76.00");
        assertThat(q.voyraNet()).isEqualByComparingTo("120.00");
    }

    @Test
    void noPricingRowMeansNotBookable() {
        when(pricingService.resolve(eq("T"), eq(PricingProduct.FLIGHT), any(Instant.class))).thenReturn(Optional.empty());
        assertThat(quoter.quoterFor("T", PricingProduct.FLIGHT)).isEmpty();
        assertThatThrownBy(() -> quote("5000", "4000")).isInstanceOf(PricingNotConfiguredException.class);
    }
}
