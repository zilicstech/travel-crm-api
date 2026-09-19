package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
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
@Schema(description = "A computed invoice line - every rate and amount is what TaxEngine returned when this line was last saved")
public class InvoiceLineItemResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "0")
    private Integer sortOrder;

    @Schema(example = "Dubai package - 4N/5D, 2 pax")
    private String description;

    @Schema(example = "9985")
    private String sacCode;

    @Schema(example = "PACKAGE")
    private ServiceType serviceType;

    @Schema(example = "1")
    private BigDecimal quantity;

    @Schema(example = "84000.00")
    private BigDecimal unitPrice;

    @Schema(example = "84000.00")
    private BigDecimal lineSubtotal;

    @Schema(example = "0.00")
    private BigDecimal discountAmount;

    @Schema(example = "100.000")
    private BigDecimal taxablePercent;

    @Schema(example = "84000.00")
    private BigDecimal taxableValue;

    @Schema(example = "5.000")
    private BigDecimal gstRatePercent;

    @Schema(example = "2.500")
    private BigDecimal cgstRatePercent;

    @Schema(example = "2.500")
    private BigDecimal sgstRatePercent;

    @Schema(example = "0.000")
    private BigDecimal igstRatePercent;

    @Schema(example = "2100.00")
    private BigDecimal cgstAmount;

    @Schema(example = "2100.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "88200.00")
    private BigDecimal lineTotal;
}
