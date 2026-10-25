package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Mark an agency wallet as a test wallet")
public class WalletTestFlagRequest {

    @NotNull
    @Schema(description = "True for a test wallet", example = "true")
    private Boolean isTest;
}
