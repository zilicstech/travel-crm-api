package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Status is not editable until the service has an assignedAgentId - a "
        + "status nobody can be asked about is not a status anyone should be able to set")
public class ServiceStatusUpdateRequest {

    @NotNull(message = "Status is required")
    @Schema(example = "CONFIRMED")
    private ServiceStatus status;
}
