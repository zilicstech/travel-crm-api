package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Activate/deactivate an account")
public class ActiveStatusUpdateRequest {

    @NotNull(message = "isActive is required")
    private Boolean isActive;
}
