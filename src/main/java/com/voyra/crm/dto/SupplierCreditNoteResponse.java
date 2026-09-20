package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
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
@Schema(description = "A credit note the supplier issued to us")
public class SupplierCreditNoteResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "IND-CN/2026/00812")
    private String supplierNoteNumber;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String supplierInvoiceId;

    @Schema(example = "IND/2026/48213")
    private String supplierInvoiceNumber;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "BOOKING_CANCELLED")
    private SupplierCreditNoteReason reason;

    @Schema(example = "Client cancelled, supplier waived 80%")
    private String reasonNote;

    @Schema(example = "RECORDED")
    private SupplierCreditNoteStatus status;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "10400.00")
    private BigDecimal taxableValue;

    @Schema(example = "260.00")
    private BigDecimal cgstAmount;

    @Schema(example = "260.00")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "2000.00")
    private BigDecimal retentionFee;

    @Schema(example = "10920.00")
    private BigDecimal totalAmount;

    @Schema(example = "10920.00")
    private BigDecimal totalAmountInr;

    @Schema(example = "10920.00")
    private BigDecimal refundableAmount;

    @Schema(example = "0.00")
    private BigDecimal refundedAmount;

    @Schema(example = "2026-09-19")
    private LocalDate noteDate;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime recordedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String recordedBy;

    @Schema(example = "2026-09-20T09:00:00")
    private LocalDateTime cancelledAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String cancelledBy;

    @Schema(example = "Entered in error")
    private String cancelReason;
}
