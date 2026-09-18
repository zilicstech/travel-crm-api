package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for activating or deactivating a vendor")
public class VendorStatusUpdateRequest {

    @NotNull(message = "isActive is required")
    @Schema(example = "false")
    private Boolean isActive;
}
