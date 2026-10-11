package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BankAccountResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

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

    @Schema(example = "1110")
    private String ledgerAccountCode;

    @Schema(example = "0.00")
    private BigDecimal openingBalance;

    @Schema(example = "true")
    private Boolean isActive;
}
