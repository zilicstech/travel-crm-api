package com.voyra.crm.service;

import com.voyra.crm.dto.ExchangeRateRequest;
import com.voyra.crm.dto.ExchangeRateResponse;
import com.voyra.crm.entity.ExchangeRate;
import com.voyra.crm.repository.ExchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A manual rate is one row per day and pair (re-entering it overwrites), and the form prefills the latest one. */
@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock private ExchangeRateRepository repo;
    @InjectMocks private ExchangeRateService service;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static ExchangeRateRequest req(String rate) {
        ExchangeRateRequest r = new ExchangeRateRequest();
        r.setRateDate(DAY);
        r.setBaseCurrency("usd");
        r.setQuoteCurrency("inr");
        r.setRate(new BigDecimal(rate));
        return r;
    }

    @Test
    void aFirstRateForTheDayCreatesAManualRowWithUpperCaseCodes() {
        when(repo.findByRateDateAndBaseCurrencyAndQuoteCurrency(DAY, "USD", "INR")).thenReturn(Optional.empty());

        ExchangeRateResponse out = service.record(req("83.25"));

        ArgumentCaptor<ExchangeRate> saved = ArgumentCaptor.forClass(ExchangeRate.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotBlank();
        assertThat(out.getBaseCurrency()).isEqualTo("USD");
        assertThat(out.getQuoteCurrency()).isEqualTo("INR");
        assertThat(out.getRate()).isEqualByComparingTo("83.25");
        assertThat(out.getSource()).isEqualTo("MANUAL");
        assertThat(out.getFetchedAt()).isNotNull();
        assertThat(out.getRateDate()).isEqualTo(DAY);
    }

    @Test
    void reEnteringTheSameDayOverwritesTheExistingRow() {
        ExchangeRate existing = ExchangeRate.builder().id("R1").rateDate(DAY).baseCurrency("USD").quoteCurrency("INR")
                .rate(new BigDecimal("82")).source("FEED").build();
        when(repo.findByRateDateAndBaseCurrencyAndQuoteCurrency(DAY, "USD", "INR")).thenReturn(Optional.of(existing));

        ExchangeRateResponse out = service.record(req("83.50"));

        verify(repo).save(existing);
        assertThat(out.getId()).isEqualTo("R1");
        assertThat(existing.getRate()).isEqualByComparingTo("83.50");
        assertThat(existing.getSource()).isEqualTo("MANUAL");
    }

    @Test
    void latestReturnsTheMostRecentRateOnOrBeforeTheDateOrNothing() {
        ExchangeRate r = ExchangeRate.builder().id("R1").rateDate(DAY).baseCurrency("USD").quoteCurrency("INR")
                .rate(new BigDecimal("83")).build();
        when(repo.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("USD", "INR", DAY))
                .thenReturn(Optional.of(r));
        when(repo.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("EUR", "INR", DAY))
                .thenReturn(Optional.empty());

        assertThat(service.latest("usd", "inr", DAY)).get().extracting(ExchangeRateResponse::getId).isEqualTo("R1");
        assertThat(service.latest("eur", "inr", DAY)).isEmpty();
    }
}
