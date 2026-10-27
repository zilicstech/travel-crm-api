package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "A shared discount or commission rule for one product")
public class PricingRuleResponse {

    @Schema(example = "8c3e1f7a-0b5d-4c1e-9f0a-2b6d4e8a1c3f")
    private String id;

    @Schema(example = "DISCOUNT")
    private PricingRuleKind kind;

    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @Schema(example = "Silver")
    private String name;

    @Schema(description = "Percentage, 0 to 100", example = "3.00")
    private BigDecimal pct;

    @Schema(description = "Commission rules: the commission may exceed Voyra's estimated earning")
    private boolean allowAboveEarned;

    @JsonProperty("isDefault")  // Lombok's isDefault() getter would otherwise serialize as "default"
    @Schema(description = "The rule every agency without an assignment is on; never deleted")
    private boolean isDefault;

    @Schema(description = "Moves on each change of pct or allowAboveEarned", example = "1")
    private int version;

    @Schema(description = "Agencies on this rule (for a Default rule, including agencies with no assignment)", example = "4")
    private long agencyCount;

    @Schema(description = "Last change")
    private Instant updatedAt;

    @Schema(description = "Who made the last change", example = "Voyra Admin")
    private String updatedByName;
}
