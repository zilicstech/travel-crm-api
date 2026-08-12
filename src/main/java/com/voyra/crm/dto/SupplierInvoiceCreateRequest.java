package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SupplierInvoiceCreateRequest {

    @NotBlank(message = "Supplier name is required")
    private String supplierName;

    @NotNull(message = "Category is required")
    private BookingType category;

    @NotNull(message = "Amount is required")
    private BigDecimal amount;

    private LocalDate dueDate;
    private String bookingRef;
}
