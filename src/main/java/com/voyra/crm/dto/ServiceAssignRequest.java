package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Owner-only reassignment to a different agent. An agent may claim a "
        + "service by accepting it themselves, but never hand it to someone else.")
public class ServiceAssignRequest {

    @NotBlank(message = "agentId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;
}
