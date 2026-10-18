package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Reverses a payment by writing a new, opposite-signed payment against the same vendor - the original is never edited or deleted.")
public class SupplierPaymentReverseRequest {

    @NotBlank(message = "A reversal reason is required")
    @Size(max = 150, message = "Keep the reason to 150 characters or fewer")
    @Schema(example = "Entered against the wrong bill")
    private String reason;
}
