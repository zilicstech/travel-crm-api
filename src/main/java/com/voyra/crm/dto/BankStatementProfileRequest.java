package com.voyra.crm.dto;

import com.voyra.crm.enums.BankAmountConvention;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BankStatementProfileRequest {

    @NotBlank(message = "bankName is required")
    @Schema(example = "HDFC Bank")
    private String bankName;

    @Schema(description = "Only CSV ships in this release", example = "CSV")
    private String fileFormat;

    @NotBlank(message = "dateFormat is required")
    @Schema(description = "A java.time.format.DateTimeFormatter pattern", example = "dd/MM/yyyy")
    private String dateFormat;

    @NotBlank(message = "columnMap is required")
    @Schema(description = "JSON object mapping logical fields (date, description, reference, amount or debit/credit, balance, positiveIsCredit) to this bank's actual CSV header names",
            example = "{\"date\":\"Txn Date\",\"description\":\"Narration\",\"reference\":\"Chq/Ref No\",\"amount\":\"Amount\",\"positiveIsCredit\":\"true\"}")
    private String columnMap;

    @NotNull(message = "amountConvention is required")
    @Schema(example = "SINGLE_SIGNED")
    private BankAmountConvention amountConvention;
}
