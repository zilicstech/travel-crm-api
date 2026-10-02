package com.voyra.crm.dto;

import com.voyra.crm.enums.BankAmountConvention;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankStatementProfileResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "HDFC Bank")
    private String bankName;

    @Schema(example = "CSV")
    private String fileFormat;

    @Schema(example = "dd/MM/yyyy")
    private String dateFormat;

    @Schema(example = "{\"date\":\"Txn Date\",\"amount\":\"Amount\"}")
    private String columnMap;

    @Schema(example = "SINGLE_SIGNED")
    private BankAmountConvention amountConvention;
}
