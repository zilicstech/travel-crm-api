package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Cancels a credit note. A DRAFT is simply withdrawn; an ISSUED one may only be cancelled if nothing has been refunded against it yet - the ledger credit it posted is reversed.")
public class CreditNoteCancelRequest {

    @NotBlank(message = "A cancellation reason is required")
    @Schema(example = "Raised against the wrong invoice")
    private String reason;
}
