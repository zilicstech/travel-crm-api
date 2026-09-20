package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One line on a supplier bill. GST fields here are RECORDED from the supplier's own document, never computed by our TaxEngine.")
public class SupplierInvoiceLineItemRequest {

    @NotBlank(message = "Description is required")
    @Schema(example = "Delhi-Dubai-Delhi, PNR ABC123")
    private String description;

    @Schema(example = "9971")
    private String sacCode;

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be positive")
    @Schema(example = "1")
    private BigDecimal quantity;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
    @Schema(example = "12400.00")
    private BigDecimal unitPrice;

    @Schema(example = "0.00")
    private BigDecimal discountAmount;

    @NotNull(message = "GST rate is required")
    @Schema(description = "As printed on the supplier's bill", example = "5.000")
    private BigDecimal gstRatePercent;

    @NotNull(message = "CGST amount is required")
    @Schema(example = "310.00")
    private BigDecimal cgstAmount;

    @NotNull(message = "SGST amount is required")
    @Schema(example = "310.00")
    private BigDecimal sgstAmount;

    @NotNull(message = "IGST amount is required")
    @Schema(example = "0.00")
    private BigDecimal igstAmount;
}
