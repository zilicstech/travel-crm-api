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
@Schema(description = "A recorded supplier bill line - every amount is what the supplier printed, not computed by us")
public class SupplierInvoiceLineItemResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "0")
    private Integer sortOrder;

    @Schema(example = "Delhi-Dubai-Delhi, PNR ABC123")
    private String description;

    @Schema(example = "9971")
    private String sacCode;

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "1")
    private BigDecimal quantity;

    @Schema(example = "12400.00")
    private BigDecimal unitPrice;

    @Schema(example = "12400.00")
    private BigDecimal lineSubtotal;

    @Schema(example = "0.00")
    private BigDecimal discountAmount;

    @Schema(example = "12400.00")
    private BigDecimal taxableValue;

    @Schema(example = "5.000")
    private BigDecimal gstRatePercent;

    @Schema(example = "310.00")
    private BigDecimal cgstAmount;

    @Schema(example = "310.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "13020.00")
    private BigDecimal lineTotal;
}
