package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@Schema(description = "The discount and commission rule an agency is on for one product")
public class AgencyPricingAssignmentResponse {

    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @Schema(description = "The discount rule in force")
    private PricingRuleResponse discountRule;

    @Schema(description = "The commission rule in force")
    private PricingRuleResponse commissionRule;

    @Schema(description = "False when the agency is on the Default rules (no assignment)")
    private boolean assigned;

    @Schema(description = "When the assignment last changed", nullable = true)
    private Instant updatedAt;

    @Schema(description = "Who changed it", nullable = true)
    private String updatedByName;
}
