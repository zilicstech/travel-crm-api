package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Schema(description = "A new discount and commission rate for one agency and product")
public class AgencyPricingCreateRequest {

    @NotNull
    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("100")
    @Schema(description = "Discount % the agency's customers see", example = "3")
    private BigDecimal discountPct;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("100")
    @Schema(description = "Commission % paid back to the agency", example = "4")
    private BigDecimal commissionPct;

    @Schema(description = "Let the commission exceed what Voyra earned; always needs a second approver", example = "false")
    private boolean allowCommissionAboveEarned;

    @NotNull
    @Schema(description = "When the rate starts; now or later", example = "2026-11-01T00:00:00Z")
    private Instant effectiveFrom;
}
