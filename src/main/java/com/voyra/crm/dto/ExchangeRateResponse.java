package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "One recorded day's reference rate for a currency pair")
public class ExchangeRateResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "2026-09-30")
    private LocalDate rateDate;

    @Schema(example = "USD")
    private String baseCurrency;

    @Schema(example = "INR")
    private String quoteCurrency;

    @Schema(example = "83.250000")
    private BigDecimal rate;

    @Schema(example = "MANUAL")
    private String source;

    @Schema(description = "When this row was written")
    private LocalDateTime fetchedAt;
}
