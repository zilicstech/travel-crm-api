package com.voyra.crm.dto;

import com.voyra.crm.enums.BankMatchedSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@Schema(description = "A probabilistic tier-2 bank-transaction match (Rule 4.3.1) - surfaced for a human to confirm, never auto-applied")
public class TierTwoCandidateResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String transactionId;

    @Schema(example = "INVOICE")
    private BankMatchedSourceType sourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @Schema(description = "The invoice or supplier-bill number, for display", example = "ITI/2026-27/0042")
    private String sourceLabel;

    @Schema(example = "42000.00")
    private BigDecimal amount;

    @Schema(description = "100 minus 25 per day of difference between the bank date and the document's due date - higher is a better candidate", example = "75.00")
    private BigDecimal score;
}
