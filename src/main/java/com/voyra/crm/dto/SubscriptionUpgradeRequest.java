package com.voyra.crm.dto;

import com.voyra.crm.enums.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Upgrade the agency to a higher plan")
public class SubscriptionUpgradeRequest {

    @NotNull(message = "plan is required")
    @Schema(description = "LITE (Voyra CRM Lite) or SUITE (Voyra Premium); must be higher than the current plan", example = "LITE")
    private SubscriptionPlan plan;
}
