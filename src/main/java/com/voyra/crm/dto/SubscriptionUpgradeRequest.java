package com.voyra.crm.dto;

import com.voyra.crm.enums.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Ask Voyra to move the agency to a higher plan")
public class SubscriptionUpgradeRequest {

    @NotNull(message = "plan is required")
    @Schema(description = "LITE (Voyra CRM Lite) or SUITE (Voyra Premium); must be higher than the current plan", example = "LITE")
    private SubscriptionPlan plan;

    @Size(max = 500, message = "note must be at most 500 characters")
    @Schema(description = "Anything Voyra should know", example = "We are hiring two agents next month", nullable = true)
    private String note;
}
