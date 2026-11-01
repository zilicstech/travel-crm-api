package com.voyra.crm.service.booking;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.util.PricingMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.BiFunction;

/** Applies the agency's pricing rules to a gross/base fare pair: the discount comes off gross, the commission is a percentage of base. */
@Service
@RequiredArgsConstructor
public class AgencyPriceQuoter {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AgencyPricingService pricingService;
    private final PlatformSettingService settings;

    public PriceQuote quote(String tenantId, PricingProduct product, BigDecimal gross, BigDecimal base) {
        return quoterFor(tenantId, product).orElseThrow(PricingNotConfiguredException::new).apply(gross, base);
    }

    /** Resolves the agency's rules and the TDS rate once so a whole result list is priced without a query per fare; empty only if a Default rule is missing. */
    public Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quoterFor(String tenantId, PricingProduct product) {
        return pricingService.resolve(tenantId, product).map(rules -> {
            BigDecimal tdsPct = settings.tdsRate();
            return (gross, base) -> price(rules, tdsPct, gross, base);
        });
    }

    private static PriceQuote price(AgencyPricingService.EffectivePricing rules, BigDecimal tdsPct, BigDecimal gross, BigDecimal base) {
        BigDecimal discountPct = rules.discount().getPct();
        BigDecimal commissionPct = rules.commission().getPct();
        BigDecimal commission = round(base.multiply(commissionPct).divide(HUNDRED));
        BigDecimal tds = round(commission.multiply(tdsPct).divide(HUNDRED));
        return new PriceQuote(PricingMath.displayed(gross, discountPct), commission.subtract(tds), commission, tds,
                rules.pricingId(), discountPct, commissionPct);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
