package com.voyra.crm.dto;

import com.voyra.crm.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Change an Agency's liveness status")
public class TenantStatusUpdateRequest {

    @NotNull(message = "status is required")
    @Schema(description = "ACTIVE, SUSPENDED (read-only) or DEACTIVATED (sign-in refused)", example = "SUSPENDED")
    private TenantStatus status;
}
