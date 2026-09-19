package com.voyra.crm.dto;

import com.voyra.crm.enums.TaxTreatment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Computed, persists nothing. Every rate here is the one an actual invoice line would freeze.")
public class TaxPreviewResponse {

    @Schema(example = "INTRA_STATE")
    private TaxTreatment taxTreatment;

    @Schema(example = "27")
    private String placeOfSupplyCode;

    @Schema(example = "50000.00")
    private BigDecimal taxableValue;

    @Schema(example = "5.000")
    private BigDecimal gstRatePercent;

    @Schema(example = "2.500")
    private BigDecimal cgstRatePercent;

    @Schema(example = "2.500")
    private BigDecimal sgstRatePercent;

    @Schema(example = "0.000")
    private BigDecimal igstRatePercent;

    @Schema(example = "1250.00")
    private BigDecimal cgstAmount;

    @Schema(example = "1250.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "2500.00")
    private BigDecimal gstTotal;

    @Schema(example = "5.000")
    private BigDecimal tcsRatePercent;

    @Schema(example = "206C(1G)")
    private String tcsSection;

    @Schema(example = "0.00")
    private BigDecimal tcsBaseAmount;

    @Schema(example = "0.00")
    private BigDecimal tcsAmount;

    @Schema(example = "52500.00")
    private BigDecimal grandTotal;
}
