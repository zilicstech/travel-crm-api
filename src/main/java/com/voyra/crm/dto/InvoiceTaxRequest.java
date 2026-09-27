package com.voyra.crm.dto;

import com.voyra.crm.enums.TaxLineMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One tax to add to a draft invoice. Picking a row from the tax dropdown "
        + "sets taxRateConfigId and pre-fills label/mode/ratePercent; a custom tax leaves "
        + "taxRateConfigId null. visibleToCustomer never changes the amount charged - only "
        + "whether the printed invoice shows this line or folds it into the fare.")
public class InvoiceTaxRequest {

    @NotBlank(message = "label is required")
    @Schema(example = "GST 18%")
    private String label;

    @Schema(description = "Set when this tax came from the tax dropdown - null for a one-off custom tax", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String taxRateConfigId;

    @NotNull(message = "mode is required")
    @Schema(example = "PERCENT")
    private TaxLineMode mode;

    @DecimalMin(value = "0.0", message = "ratePercent cannot be negative")
    @Schema(description = "Required when mode is PERCENT", example = "18.000")
    private BigDecimal ratePercent;

    @DecimalMin(value = "0.0", message = "flatAmount cannot be negative")
    @Schema(description = "Required when mode is FLAT", example = "500.00")
    private BigDecimal flatAmount;

    @Schema(description = "Whether this tax is shown as its own line on the printed customer invoice. Defaults to true.", example = "true")
    private Boolean visibleToCustomer;
}
