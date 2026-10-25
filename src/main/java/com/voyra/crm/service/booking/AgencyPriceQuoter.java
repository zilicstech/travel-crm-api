package com.voyra.crm.service.booking;

import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.PricingNotConfiguredException;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.util.PricingMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;

/** Applies the pricing engine (design spec section 5.1) to a gross/base fare pair for one agency. */
@Service
@RequiredArgsConstructor
public class AgencyPriceQuoter {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AgencyPricingService pricingService;
    private final PlatformSettingService settings;

    public PriceQuote quote(String tenantId, PricingProduct product, BigDecimal gross, BigDecimal base) {
        return quoterFor(tenantId, product).orElseThrow(PricingNotConfiguredException::new).apply(gross, base);
    }

    /** Resolves the rate and settings once so a whole result list is priced without a query per fare; empty when no rate is in force. */
    public Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quoterFor(String tenantId, PricingProduct product) {
        return pricingService.resolve(tenantId, product, Instant.now()).map(row -> {
            DiscountBase db = settings.discountBase();
            BigDecimal earnedPct = settings.commissionEstimatePct();
            BigDecimal tdsPct = settings.tdsRate();
            return (gross, base) -> price(row, db, earnedPct, tdsPct, gross, base);
        });
    }

    private static PriceQuote price(AgencyPricing row, DiscountBase db, BigDecimal earnedPct, BigDecimal tdsPct,
                                    BigDecimal gross, BigDecimal base) {
        BigDecimal displayed = PricingMath.displayed(gross, base, row.getDiscountPct(), db);
        // shortcut: voyraEarned is always the platform estimate until API/statement figures exist (spec 5.1)
        BigDecimal earned = round(base.multiply(earnedPct).divide(HUNDRED));
        BigDecimal commission = round(base.multiply(row.getCommissionPct()).divide(HUNDRED));
        if (!row.isAllowCommissionAboveEarned()) {
            commission = commission.min(earned);
        }
        BigDecimal tds = round(commission.multiply(tdsPct).divide(HUNDRED));
        BigDecimal net = PricingMath.voyraNetOf(earned, gross, displayed, commission);
        return new PriceQuote(displayed, commission.subtract(tds), earned, commission, tds, net,
                row.getId(), row.getDiscountPct(), row.getCommissionPct());
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
