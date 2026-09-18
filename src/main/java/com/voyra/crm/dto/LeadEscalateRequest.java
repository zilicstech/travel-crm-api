package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for flagging or clearing a lead's escalation")
public class LeadEscalateRequest {

    @NotNull(message = "escalated is required")
    @Schema(example = "true")
    private Boolean escalated;

    @Schema(description = "Required when escalated is true", example = "Client threatening to cancel over a pricing dispute")
    private String reason;
}
