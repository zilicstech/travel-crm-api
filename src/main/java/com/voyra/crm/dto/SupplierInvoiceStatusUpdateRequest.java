package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Owner-only request body for updating a supplier invoice's payment status")
public class SupplierInvoiceStatusUpdateRequest {

    @NotNull(message = "Status is required")
    @Schema(example = "PAID")
    private InvoiceStatus status;
}
