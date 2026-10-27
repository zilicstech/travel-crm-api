package com.voyra.crm.service.booking;

import java.math.BigDecimal;

/** One fare priced for one agency. Internal: voyraEarned and voyraNet must never reach an agency response. */
public record PriceQuote(BigDecimal displayed, BigDecimal agencyEarns, BigDecimal voyraEarned, BigDecimal agencyCommission,
                         BigDecimal tds, BigDecimal voyraNet, String pricingId, BigDecimal discountPct, BigDecimal commissionPct) {
}
