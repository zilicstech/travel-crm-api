package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SupplierInvoiceStatusUpdateRequest {

    @NotNull(message = "Status is required")
    private InvoiceStatus status;
}
