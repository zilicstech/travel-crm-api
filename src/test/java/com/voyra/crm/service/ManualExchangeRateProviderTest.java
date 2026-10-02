package com.voyra.crm.service;

import com.voyra.crm.entity.ExchangeRate;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.ExchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualExchangeRateProviderTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @InjectMocks
    private ManualExchangeRateProvider provider;

    @Test
    void identicalBaseAndQuoteResolvesToOneWithoutTouchingTheTable() {
        ResolvedExchangeRate result = provider.resolve("INR", "inr", LocalDate.of(2026, 9, 30));

        assertThat(result.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.source()).isEqualTo(FxRateSource.INR_IDENTITY);
    }

    @Test
    void exactDateHitReturnsDailyTableSource() {
        LocalDate date = LocalDate.of(2026, 9, 30);
        when(exchangeRateRepository.findByRateDateAndBaseCurrencyAndQuoteCurrency(date, "USD", "INR"))
                .thenReturn(Optional.of(ExchangeRate.builder()
                        .id("R1").rateDate(date).baseCurrency("USD").quoteCurrency("INR")
                        .rate(new BigDecimal("83.250000")).build()));

        ResolvedExchangeRate result = provider.resolve("USD", "INR", date);

        assertThat(result.rate()).isEqualByComparingTo("83.250000");
        assertThat(result.source()).isEqualTo(FxRateSource.DAILY_TABLE);
        assertThat(result.asOfDate()).isEqualTo(date);
    }

    @Test
    void noExactMatchFallsBackToMostRecentPriorDateAsStale() {
        LocalDate requested = LocalDate.of(2026, 9, 30);
        LocalDate staleDate = LocalDate.of(2026, 9, 26);
        when(exchangeRateRepository.findByRateDateAndBaseCurrencyAndQuoteCurrency(requested, "EUR", "INR"))
                .thenReturn(Optional.empty());
        when(exchangeRateRepository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                "EUR", "INR", requested))
                .thenReturn(Optional.of(ExchangeRate.builder()
                        .id("R2").rateDate(staleDate).baseCurrency("EUR").quoteCurrency("INR")
                        .rate(new BigDecimal("90.100000")).build()));

        ResolvedExchangeRate result = provider.resolve("EUR", "INR", requested);

        assertThat(result.rate()).isEqualByComparingTo("90.100000");
        assertThat(result.source()).isEqualTo(FxRateSource.STALE_TABLE);
        assertThat(result.asOfDate()).isEqualTo(staleDate);
    }

    @Test
    void noRateAtAllForThePairThrowsRatherThanDefaultingToIdentity() {
        LocalDate requested = LocalDate.of(2026, 9, 30);
        when(exchangeRateRepository.findByRateDateAndBaseCurrencyAndQuoteCurrency(requested, "AED", "INR"))
                .thenReturn(Optional.empty());
        when(exchangeRateRepository.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                "AED", "INR", requested))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.resolve("AED", "INR", requested))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AED/INR");
    }
}
