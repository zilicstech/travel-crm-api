package com.voyra.crm.dto;

import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
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
@Schema(description = "One computed tax row on an invoice")
public class InvoiceTaxResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "GST 18%")
    private String label;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String taxRateConfigId;

    @Schema(example = "GST")
    private TaxKind taxKind;

    @Schema(example = "PERCENT")
    private TaxLineMode mode;

    @Schema(example = "18.000")
    private BigDecimal ratePercent;

    @Schema(example = "0.00")
    private BigDecimal flatAmount;

    @Schema(example = "0.00")
    private BigDecimal cgstAmount;

    @Schema(example = "0.00")
    private BigDecimal sgstAmount;

    @Schema(example = "15120.00")
    private BigDecimal igstAmount;

    @Schema(example = "15120.00")
    private BigDecimal amount;

    @Schema(example = "true")
    private Boolean visibleToCustomer;
}
