package com.voyra.crm.service;

import com.voyra.crm.models.ResolvedExchangeRate;

import java.time.LocalDate;

/**
 * Supplier abstraction over "what was the rate for this pair on this date" - same shape as
 * {@code FlightSearchProvider}/{@code MockFlightSearchProvider}, so a live rate feed is a new
 * implementation behind a property, no caller changes.
 */
public interface ExchangeRateProvider {

    /**
     * Never returns a silent 1.0 identity rate for a non-INR pair with no table data at all -
     * throws instead (ACCOUNTING_EXPANSION_ARCHITECTURE.md Rule 3.2.3). An identity pair
     * (base == quote) always resolves to 1.0 without touching the table.
     */
    ResolvedExchangeRate resolve(String baseCurrency, String quoteCurrency, LocalDate rateDate);
}
