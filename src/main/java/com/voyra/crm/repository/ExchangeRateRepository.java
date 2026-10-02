package com.voyra.crm.repository;

import com.voyra.crm.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, String> {

    Optional<ExchangeRate> findByRateDateAndBaseCurrencyAndQuoteCurrency(
            LocalDate rateDate, String baseCurrency, String quoteCurrency);

    Optional<ExchangeRate> findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            String baseCurrency, String quoteCurrency, LocalDate rateDate);
}
