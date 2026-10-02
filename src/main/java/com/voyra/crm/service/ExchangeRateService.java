package com.voyra.crm.service;

import com.voyra.crm.dto.ExchangeRateRequest;
import com.voyra.crm.dto.ExchangeRateResponse;
import com.voyra.crm.entity.ExchangeRate;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.repository.ExchangeRateRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manual entry point for the public {@code exchange_rate} table, until a live rate feed exists
 * (see {@code ExchangeRateFetchJob}). Upserts by {@code (rate_date, base_currency,
 * quote_currency)} - re-recording the same day corrects it rather than duplicating.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;

    @Transactional
    public ExchangeRateResponse record(ExchangeRateRequest request) {
        String base = request.getBaseCurrency().toUpperCase();
        String quote = request.getQuoteCurrency().toUpperCase();
        Optional<ExchangeRate> existing = exchangeRateRepository
                .findByRateDateAndBaseCurrencyAndQuoteCurrency(request.getRateDate(), base, quote);

        ExchangeRate row = existing.orElseGet(() -> ExchangeRate.builder()
                .id(UniqueIdResolver.resolve(exchangeRateRepository::existsById))
                .rateDate(request.getRateDate())
                .baseCurrency(base)
                .quoteCurrency(quote)
                .build());
        row.setRate(request.getRate());
        row.setSource(FxRateSource.MANUAL.name());
        row.setFetchedAt(LocalDateTime.now());
        exchangeRateRepository.save(row);

        log.info("Exchange rate recorded: {}/{} on {} = {}", base, quote, request.getRateDate(), request.getRate());
        return toResponse(row);
    }

    private static ExchangeRateResponse toResponse(ExchangeRate r) {
        return ExchangeRateResponse.builder()
                .id(r.getId()).rateDate(r.getRateDate())
                .baseCurrency(r.getBaseCurrency()).quoteCurrency(r.getQuoteCurrency())
                .rate(r.getRate()).source(r.getSource()).fetchedAt(r.getFetchedAt())
                .build();
    }
}
