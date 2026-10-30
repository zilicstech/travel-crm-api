package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A supplier's commission terms for one product")
public class SupplierProductTerms {

    @NotNull(message = "Product is required")
    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @NotNull(message = "Commission % is required")
    @DecimalMin(value = "0", message = "Commission % must be between 0 and 100")
    @DecimalMax(value = "100", message = "Commission % must be between 0 and 100")
    @Schema(description = "What Voyra expects to earn from the supplier, % of the base fare", example = "5")
    private BigDecimal commissionEstimatePct;
}
