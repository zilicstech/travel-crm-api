package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Whether a supplier is searched, and takes bookings, for one product")
public class SupplierProductSettings {

    @NotNull(message = "Product is required")
    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @Schema(description = "Every agency's searches for this product include the supplier", example = "true")
    private boolean searchEnabled;

    @Schema(description = "Agencies may book this product with the supplier", example = "true")
    private boolean bookingEnabled;
}
