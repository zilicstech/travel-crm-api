package com.voyra.crm.dto;

import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Change an agency's plan, extra seats and billing cycle")
public class SubscriptionUpdateRequest {

    @NotNull(message = "plan is required")
    @Schema(description = "STARTER (portal only), LITE (CRM Core, seats, reports, visa; 3 seats included), or SUITE (every SKU, 5 seats included)", example = "LITE")
    private SubscriptionPlan plan;

    @NotNull(message = "seats is required")
    @Min(value = 0, message = "seats cannot be negative")
    @Schema(description = "SUITE: extra seats on top of the 5 included; LITE: on top of the 3 included", example = "3")
    private Integer seats;

    @NotNull(message = "billingCycle is required")
    @Schema(description = "MONTHLY or ANNUAL", example = "MONTHLY")
    private BillingCycle billingCycle;
}
