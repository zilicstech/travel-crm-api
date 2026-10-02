package com.voyra.crm.models;

import com.voyra.crm.enums.FxRateSource;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What {@code ExchangeRateProvider} hands back - the rate plus how current it actually is. */
public record ResolvedExchangeRate(BigDecimal rate, FxRateSource source, LocalDate asOfDate) {
}
