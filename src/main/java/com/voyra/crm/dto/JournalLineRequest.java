package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One debit or credit line of a journal entry - never both.")
public class JournalLineRequest {

    @NotBlank(message = "Account code is required")
    @Schema(example = "1110")
    private String accountCode;

    @Schema(description = "CLIENT or VENDOR, when this line is against a party control account", example = "CLIENT")
    private String partyType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String partyId;

    @Schema(example = "INR", defaultValue = "INR")
    private String currencyCode;

    @Schema(example = "1.000000", defaultValue = "1")
    private BigDecimal fxRateToInr;

    @NotNull(message = "Debit amount is required (zero if this is a credit line)")
    @Schema(example = "0.00")
    private BigDecimal debitAmount;

    @NotNull(message = "Credit amount is required (zero if this is a debit line)")
    @Schema(example = "50000.00")
    private BigDecimal creditAmount;

    @Schema(example = "Opening balance brought forward")
    private String narration;
}
