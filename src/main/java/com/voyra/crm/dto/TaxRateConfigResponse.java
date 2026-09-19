package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One GST or TCS slab, effective-dated")
public class TaxRateConfigResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "GST")
    private TaxKind taxKind;

    @Schema(example = "Domestic tour package (abated)")
    private String label;

    @Schema(example = "9985")
    private String sacCode;

    @Schema(example = "DOMESTIC_PACKAGE")
    private SupplyNature supplyNature;

    @Schema(example = "5.000")
    private BigDecimal ratePercent;

    @Schema(example = "100.000")
    private BigDecimal taxablePercent;

    @Schema(example = "700000.00")
    private BigDecimal thresholdAmount;

    @Schema(example = "206C(1G)")
    private String tcsSection;

    @Schema(example = "2026-04-01")
    private LocalDate effectiveFrom;

    @Schema(description = "Set once this row is superseded by a newer version", example = "2027-03-31")
    private LocalDate effectiveTo;

    @Schema(example = "true")
    private Boolean isDefault;

    @Schema(example = "true")
    private Boolean isActive;
}
