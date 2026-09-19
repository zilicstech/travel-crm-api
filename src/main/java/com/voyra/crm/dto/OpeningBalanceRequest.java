package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Onboards a client with a pre-existing balance. Positive amount = the client owes the agency; negative = the agency holds a credit for the client. Posts exactly one OPENING_BALANCE ledger row.")
public class OpeningBalanceRequest {

    @NotNull(message = "amount is required")
    @Schema(example = "15000.00")
    private BigDecimal amount;

    @NotNull(message = "asOfDate is required")
    @Schema(example = "2026-04-01")
    private LocalDate asOfDate;

    @Schema(example = "Balance carried over from the previous booking system")
    private String note;
}
