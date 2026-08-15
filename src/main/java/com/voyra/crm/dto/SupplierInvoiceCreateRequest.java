package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Owner-only request body for recording a Supplier Invoice")
public class SupplierInvoiceCreateRequest {

    @NotBlank(message = "Supplier name is required")
    @Schema(example = "Cleartrip")
    private String supplierName;

    @NotNull(message = "Category is required")
    @Schema(example = "HOTEL")
    private BookingType category;

    @NotNull(message = "Amount is required")
    @Schema(example = "18000.00")
    private BigDecimal amount;

    @Schema(example = "2026-09-15")
    private LocalDate dueDate;

    @Schema(description = "Optional link to the booking this invoice covers", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingRef;
}
