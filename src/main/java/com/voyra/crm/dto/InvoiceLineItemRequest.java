package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One line on a draft invoice. Tax fields are not supplied here - TaxEngine computes them from the header's supplyNature and this line's amount on every save.")
public class InvoiceLineItemRequest {

    @NotBlank(message = "Description is required")
    @Schema(example = "Dubai package - 4N/5D, 2 pax")
    private String description;

    @Schema(example = "9985")
    private String sacCode;

    @Schema(example = "PACKAGE")
    private ServiceType serviceType;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be positive")
    @Schema(example = "1")
    private BigDecimal quantity;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
    @Schema(example = "84000.00")
    private BigDecimal unitPrice;

    @Schema(description = "Flat discount on this line, before tax", example = "0.00")
    private BigDecimal discountAmount;
}
