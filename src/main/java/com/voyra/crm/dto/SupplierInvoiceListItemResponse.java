package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.SupplierInvoiceKind;
import com.voyra.crm.enums.SupplierInvoiceStatus;
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
@Schema(description = "One row of the payables worklist - no line items, see SupplierInvoiceResponse for the full document")
public class SupplierInvoiceListItemResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "PURCHASE")
    private SupplierInvoiceKind kind;

    @Schema(example = "FLIGHT")
    private BookingType category;

    @Schema(example = "APPROVED")
    private SupplierInvoiceStatus status;

    @Schema(example = "IND/2026/48213")
    private String supplierInvoiceNumber;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "13020.00")
    private BigDecimal grandTotal;

    @Schema(example = "13020.00")
    private BigDecimal grandTotalInr;

    @Schema(example = "13020.00")
    private BigDecimal balanceDue;

    @Schema(example = "13020.00")
    private BigDecimal balanceDueInr;

    @Schema(example = "2026-09-18")
    private LocalDate invoiceDate;

    @Schema(example = "2026-10-03")
    private LocalDate dueDate;

    @Schema(example = "false")
    private Boolean overdue;
}
