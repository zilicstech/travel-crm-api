package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Reverses a receipt by writing a new, opposite-signed receipt against the same invoice - the original is never edited or deleted.")
public class PaymentReceiptReverseRequest {

    @NotBlank(message = "A reversal reason is required")
    @Size(max = 150, message = "Keep the reason to 150 characters or fewer")
    @Schema(example = "Entered against the wrong invoice")
    private String reason;
}
