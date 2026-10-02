package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Optional - only needed when the bill exceeds the booking's quoted cost cap for its vendor and the caller is an Agency Owner overriding that cap.")
public class SupplierInvoiceApproveRequest {

    @Schema(example = "Supplier raised the fare after ticketing; confirmed with the DMC by email.")
    private String overrideReason;
}
