package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "A new GST or TCS slab. Also used to replace an existing one (PUT) - the "
        + "old row is closed and this becomes the new default for its (taxKind, supplyNature) pair.")
public class TaxRateConfigCreateRequest {

    @NotNull(message = "taxKind is required")
    @Schema(example = "GST")
    private TaxKind taxKind;

    @NotBlank(message = "Label is required")
    @Schema(example = "Domestic tour package (abated)")
    private String label;

    @Schema(description = "Required for GST rows, ignored for TCS", example = "9985")
    private String sacCode;

    @NotNull(message = "supplyNature is required")
    @Schema(example = "DOMESTIC_PACKAGE")
    private SupplyNature supplyNature;

    @NotNull(message = "ratePercent is required")
    @DecimalMin(value = "0.0", message = "ratePercent cannot be negative")
    @Schema(example = "5.000")
    private BigDecimal ratePercent;

    @Schema(description = "Tour-operator abatement - the % of the line actually taxable. 100 unless a scheme applies.", example = "100.000")
    private BigDecimal taxablePercent;

    @Schema(description = "TCS only: per-client, per-financial-year threshold before this slab applies", example = "700000.00")
    private BigDecimal thresholdAmount;

    @Schema(description = "TCS only", example = "206C(1G)")
    private String tcsSection;

    @NotNull(message = "effectiveFrom is required")
    @Schema(example = "2026-04-01")
    private LocalDate effectiveFrom;

    @Schema(description = "Whether this becomes the slab TaxEngine falls back to for this (taxKind, supplyNature) pair", example = "true")
    private Boolean isDefault;
}
