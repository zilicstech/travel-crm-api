package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Void an unpaid subscription invoice")
public class SubscriptionInvoiceVoidRequest {

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Why it is voided (audited)", example = "Plan changed before payment")
    private String reason;
}
