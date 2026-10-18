package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@Schema(description = "What an invoice may draw from its client's money on account")
public class InvoiceWalletResponse {

    @Schema(description = "The client's money on account this invoice may use: the pool less what is held for the client's other bookings", example = "8850.00")
    private BigDecimal availableInr;

    @Schema(description = "A deposit on account to name when applying it (audit trail only); null when the client has none", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String advanceReceiptId;
}
