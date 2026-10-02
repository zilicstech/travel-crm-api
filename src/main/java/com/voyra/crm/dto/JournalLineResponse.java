package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One posted debit or credit line.")
public class JournalLineResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "1")
    private Integer lineNo;

    @Schema(example = "1110")
    private String accountCode;

    @Schema(example = "CLIENT")
    private String partyType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String partyId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "0.00")
    private BigDecimal debitAmount;

    @Schema(example = "50000.00")
    private BigDecimal creditAmount;

    @Schema(example = "0.00")
    private BigDecimal debitAmountInr;

    @Schema(example = "50000.00")
    private BigDecimal creditAmountInr;

    @Schema(example = "Opening balance brought forward")
    private String narration;
}
