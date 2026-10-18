package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Only an ISSUED invoice with no receipts against it can be cancelled - a settled invoice is corrected with a credit note instead.")
public class InvoiceCancelRequest {

    @NotBlank(message = "A cancellation reason is required")
    @Size(max = 150, message = "Keep the reason to 150 characters or fewer")
    @Schema(example = "Raised against the wrong booking")
    private String reason;
}
