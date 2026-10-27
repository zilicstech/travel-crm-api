package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Mark a subscription invoice paid by bank transfer")
public class SubscriptionInvoicePaidRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Bank reference (UTR) of the payment", example = "SBIN0012345678")
    private String reference;
}
