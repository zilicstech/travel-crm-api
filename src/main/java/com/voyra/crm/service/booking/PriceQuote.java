package com.voyra.crm.service.booking;

import java.math.BigDecimal;

/** One fare priced for one agency. */
public record PriceQuote(BigDecimal displayed, BigDecimal agencyEarns, BigDecimal agencyCommission, BigDecimal tds,
                         String pricingId, BigDecimal discountPct, BigDecimal commissionPct) {
}
