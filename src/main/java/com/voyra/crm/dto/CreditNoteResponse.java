package com.voyra.crm.dto;

import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
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
@Schema(description = "One credit_note row. Amounts are frozen at issue and never recomputed afterwards.")
public class CreditNoteResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Null until issued", example = "CN/2026-27/0001")
    private String creditNoteNumber;

    @Schema(example = "2026-27")
    private String financialYear;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String invoiceId;

    @Schema(example = "INV/2026-27/0006")
    private String invoiceNumber;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Rahul Verma")
    private String clientName;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "BOOKING_CANCELLED")
    private CreditNoteReason reason;

    @Schema(example = "Client cancelled 10 days before departure")
    private String reasonNote;

    @Schema(example = "DRAFT")
    private CreditNoteStatus status;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "83120.00")
    private BigDecimal taxableValue;

    @Schema(example = "7480.80")
    private BigDecimal cgstAmount;

    @Schema(example = "7480.80")
    private BigDecimal sgstAmount;

    @Schema(example = "0.00")
    private BigDecimal igstAmount;

    @Schema(example = "0.00")
    private BigDecimal tcsAmount;

    @Schema(description = "Retained taxable value, not credited", example = "0.00")
    private BigDecimal cancellationFee;

    @Schema(example = "98081.60")
    private BigDecimal totalAmount;

    @Schema(example = "98081.60")
    private BigDecimal totalAmountInr;

    @Schema(description = "Equal to totalAmount unless a future release adds a non-cash settlement path", example = "98081.60")
    private BigDecimal refundableAmount;

    @Schema(description = "Cumulative REFUND-direction receipts posted against this credit note", example = "0.00")
    private BigDecimal refundedAmount;

    @Schema(example = "2026-09-19")
    private LocalDate noteDate;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime issuedAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String issuedBy;

    @Schema(description = "Set only if this credit note was cancelled")
    private LocalDateTime cancelledAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String cancelledBy;

    @Schema(example = "Raised in error")
    private String cancelReason;
}
