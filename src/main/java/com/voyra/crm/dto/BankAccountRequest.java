package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BankAccountRequest {

    @NotBlank(message = "accountName is required")
    @Schema(example = "HDFC Current A/c 001")
    private String accountName;

    @Schema(example = "50100123456789")
    private String accountNumber;

    @Schema(example = "HDFC0000123")
    private String ifsc;

    @Schema(example = "MG Road Branch")
    private String branch;

    @Schema(example = "INR")
    private String currencyCode;

    @NotBlank(message = "ledgerAccountCode is required")
    @Schema(description = "The GL account code this bank account posts against - a child of 1110", example = "1110")
    private String ledgerAccountCode;

    @Schema(example = "0.00")
    private BigDecimal openingBalance;
}
