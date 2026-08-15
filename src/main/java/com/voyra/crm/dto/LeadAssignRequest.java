package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Owner-only request body for reassigning a Lead to a different agent")
public class LeadAssignRequest {

    @NotBlank(message = "agentId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;
}
