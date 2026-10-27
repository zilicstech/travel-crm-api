package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Put an agency on a discount rule and a commission rule for a product")
public class AgencyPricingAssignRequest {

    @NotBlank(message = "discountRuleId is required")
    @Schema(example = "8c3e1f7a-0b5d-4c1e-9f0a-2b6d4e8a1c3f")
    private String discountRuleId;

    @NotBlank(message = "commissionRuleId is required")
    @Schema(example = "1d2e3f4a-5b6c-7d8e-9f0a-1b2c3d4e5f6a")
    private String commissionRuleId;
}
