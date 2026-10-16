package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "One-step supplier payment for a booking: the supplier bill is drafted and approved behind the scenes when needed")
public class BookingSupplierPaymentRequest {

    @Schema(description = "Supplier to pay. Defaults to the booking's supplier; required when the booking has none.")
    private String vendorId;

    @Schema(description = "Pay this specific bill. Needed only when the booking has several open bills.")
    private String supplierInvoiceId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be more than zero")
    @Schema(example = "8000.00")
    private BigDecimal amount;

    @NotNull(message = "Payment mode is required")
    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @NotNull(message = "Date is required")
    @Schema(example = "2026-10-17")
    private LocalDate paidOn;

    @Schema(description = "UTR, cheque no. or similar", example = "UTR123456")
    private String reference;

    @Schema(description = "The number printed on the supplier's own invoice", example = "INV-2231")
    private String supplierInvoiceNumber;

    @Schema(description = "Owner only: approves a bill above the booking's cost cap")
    private String overrideReason;
}
