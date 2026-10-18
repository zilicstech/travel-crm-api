package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Reason for cancelling a supplier bill - only allowed when no payment has been recorded against it")
public class SupplierInvoiceCancelRequest {

    @NotBlank(message = "Reason is required")
    @Size(max = 150, message = "Keep the reason to 150 characters or fewer")
    @Schema(example = "Duplicate entry")
    private String reason;
}
