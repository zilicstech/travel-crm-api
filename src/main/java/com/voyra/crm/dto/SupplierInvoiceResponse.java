package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceStatus;
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
@Schema(description = "A Supplier Invoice (accounts payable), owner-only")
public class SupplierInvoiceResponse {

    @Schema(example = "W8X4Y1")
    private String id;

    @Schema(example = "Cleartrip")
    private String supplierName;

    @Schema(example = "HOTEL")
    private BookingType category;

    @Schema(example = "18000.00")
    private BigDecimal amount;

    @Schema(example = "PENDING")
    private InvoiceStatus status;

    @Schema(example = "2026-09-15")
    private LocalDate dueDate;

    @Schema(description = "Optional link to the booking this invoice covers", example = "P4Q7R2")
    private String bookingRef;

    @Schema(description = "True when dueDate has passed and status is not PAID")
    private boolean overdue;
}
