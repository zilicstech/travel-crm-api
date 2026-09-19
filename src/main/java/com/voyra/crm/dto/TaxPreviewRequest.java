package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplyNature;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Runs TaxEngine against a client and a taxable amount without persisting "
        + "anything - what the invoice builder calls live as the accountant fills the form.")
public class TaxPreviewRequest {

    @NotBlank(message = "clientId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @NotNull(message = "taxableAmount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "taxableAmount must be positive")
    @Schema(description = "GST-exclusive amount for this line", example = "50000.00")
    private BigDecimal taxableAmount;

    @NotNull(message = "supplyNature is required")
    @Schema(example = "DOMESTIC_PACKAGE")
    private SupplyNature supplyNature;

    @Schema(description = "Overrides the client's own state for place-of-supply. Defaults to the client's state, then the agency's.", example = "27")
    private String placeOfSupplyCodeOverride;

    @Schema(description = "Explicit accountant request for export-of-service treatment - only honoured when the client is overseas and the currency is non-INR", example = "false")
    private Boolean exportOfServiceRequested;

    @Schema(example = "INR", defaultValue = "INR")
    private String currencyCode;
}
