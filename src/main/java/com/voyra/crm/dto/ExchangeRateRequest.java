package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Record one day's reference rate for a currency pair - platform-wide, not per-agency")
public class ExchangeRateRequest {

    @NotNull(message = "rateDate is required")
    @Schema(example = "2026-09-30")
    private LocalDate rateDate;

    @NotBlank(message = "baseCurrency is required")
    @Size(min = 3, max = 3)
    @Schema(example = "USD")
    private String baseCurrency;

    @NotBlank(message = "quoteCurrency is required")
    @Size(min = 3, max = 3)
    @Schema(example = "INR")
    private String quoteCurrency;

    @NotNull(message = "rate is required")
    @Positive(message = "rate must be positive")
    @Schema(example = "83.250000")
    private BigDecimal rate;
}
