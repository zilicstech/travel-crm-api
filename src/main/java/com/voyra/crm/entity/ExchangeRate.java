package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day's reference rate for a currency pair - public schema, shared across every tenant
 * (ACCOUNTING_EXPANSION_ARCHITECTURE.md Decision 3.1). Read via the plain {@code tenant_<id>,
 * public} search_path already applied to every tenant-scoped connection - no schema
 * qualification needed here.
 */
@Entity
@Table(name = "exchange_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ExchangeRate {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
