package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Reason for cancelling a recorded supplier credit note - only allowed before any refund has been received against it")
public class SupplierCreditNoteCancelRequest {

    @NotBlank(message = "Reason is required")
    @Schema(example = "Entered in error")
    private String reason;
}
