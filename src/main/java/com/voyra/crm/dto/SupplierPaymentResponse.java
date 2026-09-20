package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierPaymentDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One supplier_payment row - append-only. A reversed payment still shows its own original amount; the reversing row is a separate, opposite-signed entry.")
public class SupplierPaymentResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "PV/2026-27/0001")
    private String voucherNumber;

    @Schema(example = "2026-27")
    private String financialYear;

    @Schema(example = "PAYMENT")
    private SupplierPaymentDirection direction;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String supplierInvoiceId;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "13020.00")
    private BigDecimal amount;

    @Schema(example = "13020.00")
    private BigDecimal amountInr;

    @Schema(example = "0.00")
    private BigDecimal tdsWithheld;

    @Schema(example = "BANK_TRANSFER")
    private PaymentMode paymentMode;

    @Schema(example = "UTR2026091912345")
    private String instrumentRef;

    @Schema(example = "HDFC Current A/c 001")
    private String bankAccountLabel;

    @Schema(example = "2026-09-19")
    private LocalDate paidOn;

    @Schema(example = "false")
    private Boolean isAdvance;

    @Schema(example = "false")
    private Boolean appliedFromAdvance;

    @Schema(description = "Set on the reversing row only, pointing at the payment it reverses")
    private String reversesPaymentId;

    @Schema(description = "Set on the original once a reversing row exists against it")
    private LocalDateTime reversedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String reversedBy;

    @Schema(example = "Entered against the wrong bill")
    private String reversalReason;

    @Schema(example = "Advance for October bookings")
    private String notes;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime createdAt;
}
