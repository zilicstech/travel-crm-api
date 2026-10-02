package com.voyra.crm.dto;

import com.voyra.crm.enums.BankMatchedSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfirmBankMatchRequest {

    @NotNull(message = "sourceType is required")
    @Schema(example = "INVOICE")
    private BankMatchedSourceType sourceType;

    @NotBlank(message = "sourceId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;
}
