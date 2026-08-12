package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceStatus;
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
public class SupplierInvoiceResponse {

    private String id;
    private String supplierName;
    private BookingType category;
    private BigDecimal amount;
    private InvoiceStatus status;
    private LocalDate dueDate;
    private String bookingRef;
    private boolean overdue;
}
