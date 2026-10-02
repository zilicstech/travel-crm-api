package com.voyra.crm.dto;

import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class BankTransactionResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "2026-09-20")
    private LocalDate txnDate;

    @Schema(example = "NEFT FROM RAKESH VERMA")
    private String description;

    @Schema(example = "UTR2026091912345")
    private String bankReference;

    @Schema(example = "42000.00")
    private BigDecimal amount;

    @Schema(example = "CREDIT")
    private BankTransactionDirection direction;

    @Schema(example = "UNMATCHED")
    private BankTransactionMatchStatus matchStatus;

    @Schema(example = "INVOICE")
    private BankMatchedSourceType matchedSourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String matchedSourceId;
}
