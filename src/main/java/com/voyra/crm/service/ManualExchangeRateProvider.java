package com.voyra.crm.service;

import com.voyra.crm.entity.ExchangeRate;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Default {@link ExchangeRateProvider} - reads the public {@code exchange_rate} table a human
 * (or a future live feed) populated. Exact same-day hit first; falls back to the most recent
 * prior day for that pair. Never defaults to 1.0 for a real cross-currency pair with no data at
 * all - that would silently understate a foreign cost by the whole exchange rate
 * (Rule 3.2.3), so it throws instead and lets the caller decide (manual entry, retry later).
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.fx.rate-provider", havingValue = "manual", matchIfMissing = true)
public class ManualExchangeRateProvider implements ExchangeRateProvider {

    private final ExchangeRateRepository exchangeRateRepository;

    @Override
    @Transactional(readOnly = true)
    public ResolvedExchangeRate resolve(String baseCurrency, String quoteCurrency, LocalDate rateDate) {
        if (baseCurrency.equalsIgnoreCase(quoteCurrency)) {
            return new ResolvedExchangeRate(BigDecimal.ONE, FxRateSource.INR_IDENTITY, rateDate);
        }

        return exchangeRateRepository
                .findByRateDateAndBaseCurrencyAndQuoteCurrency(rateDate, baseCurrency, quoteCurrency)
                .map(r -> new ResolvedExchangeRate(r.getRate(), FxRateSource.DAILY_TABLE, r.getRateDate()))
                .or(() -> exchangeRateRepository
                        .findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                                baseCurrency, quoteCurrency, rateDate)
                        .map(r -> new ResolvedExchangeRate(r.getRate(), FxRateSource.STALE_TABLE, r.getRateDate())))
                .orElseThrow(() -> new IllegalStateException(
                        "No exchange rate on or before " + rateDate + " for " + baseCurrency + "/" + quoteCurrency
                                + " - record one before proceeding, do not default to 1.0"));
    }
}
