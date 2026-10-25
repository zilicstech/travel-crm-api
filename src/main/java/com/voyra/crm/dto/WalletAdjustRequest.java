package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "A signed admin correction to an agency wallet")
public class WalletAdjustRequest {

    @NotNull
    @NonZero
    @Digits(integer = 17, fraction = 2)
    @Schema(description = "Positive credits the wallet, negative debits it", example = "-250.00")
    private BigDecimal amount;

    @NotBlank
    @Size(min = 3, max = 300)
    @Schema(description = "Why the correction is made; shown on the agency statement", example = "Duplicate top-up reversed")
    private String reason;

    @NotBlank
    @Size(min = 8, max = 64)
    @Schema(description = "Client-generated id; resubmitting the same id posts once", example = "5b1f0c1e-9a3d-4c55-8d0e-2f6c1a7b9e11")
    private String requestId;
}
