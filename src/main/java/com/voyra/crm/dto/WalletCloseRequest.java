package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Close an agency wallet and pay the balance out")
public class WalletCloseRequest {

    @NotBlank
    @Size(min = 4, max = 40)
    @Schema(description = "Bank UTR of the closure refund transfer", example = "UTIB2610250001")
    private String utr;
}
