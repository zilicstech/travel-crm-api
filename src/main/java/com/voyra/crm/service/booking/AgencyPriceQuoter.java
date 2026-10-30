package com.voyra.crm.service.booking;

import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.util.PricingMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.BiFunction;

/** Applies the pricing engine (design spec section 5.1) to a gross/base fare pair for one agency. */
@Service
@RequiredArgsConstructor
public class AgencyPriceQuoter {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AgencyPricingService pricingService;
    private final PlatformSettingService settings;
    private final PlatformSupplierCredentialService suppliers;

    public PriceQuote quote(String tenantId, PricingProduct product, BigDecimal gross, BigDecimal base) {
        return quoterFor(tenantId, product).orElseThrow(PricingNotConfiguredException::new).apply(gross, base);
    }

    /** Resolves the agency's rules, settings and supplier terms once so a whole result list is priced without a query per fare; empty only if a Default rule is missing. */
    public Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quoterFor(String tenantId, PricingProduct product) {
        return pricingService.resolve(tenantId, product).map(rules -> {
            DiscountBase db = settings.discountBase();
            BigDecimal earnedPct = suppliers.commissionEstimatePct(product);
            BigDecimal tdsPct = settings.tdsRate();
            return (gross, base) -> price(rules, db, earnedPct, tdsPct, gross, base);
        });
    }

    private static PriceQuote price(AgencyPricingService.EffectivePricing rules, DiscountBase db, BigDecimal earnedPct, BigDecimal tdsPct,
                                    BigDecimal gross, BigDecimal base) {
        BigDecimal discountPct = rules.discount().getPct();
        BigDecimal commissionPct = rules.commission().getPct();
        BigDecimal displayed = PricingMath.displayed(gross, base, discountPct, db);
        // shortcut: voyraEarned is always the platform estimate until API/statement figures exist (spec 5.1)
        BigDecimal earned = round(base.multiply(earnedPct).divide(HUNDRED));
        BigDecimal commission = round(base.multiply(commissionPct).divide(HUNDRED));
        if (!rules.commission().isAllowAboveEarned()) {
            commission = commission.min(earned);
        }
        BigDecimal tds = round(commission.multiply(tdsPct).divide(HUNDRED));
        BigDecimal net = PricingMath.voyraNetOf(earned, gross, displayed, commission);
        return new PriceQuote(displayed, commission.subtract(tds), earned, commission, tds, net,
                rules.pricingId(), discountPct, commissionPct);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
