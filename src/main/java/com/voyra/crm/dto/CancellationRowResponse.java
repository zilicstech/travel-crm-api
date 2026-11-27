package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A booking on the Cancellations tab; no Voyra fee or supplier figures")
public class CancellationRowResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "BK00000042")
    private String bookingRef;

    @Schema(example = "FLIGHT")
    private BookingType type;

    @Schema(example = "Ajay Sharma")
    private String clientName;

    @Schema(example = "Dubai")
    private String destination;

    @Schema(example = "2026-12-01")
    private LocalDate travelDate;

    @Schema(example = "Vikram Rao")
    private String agentName;

    @Schema(example = "CANCELLATION_PENDING")
    private CancellationStatus status;

    @Schema(description = "An open follow-up is due today or overdue", example = "false")
    private boolean followUpDue;

    @Schema(description = "The earliest open follow-up or scheduled cancellation")
    private CancellationFollowUpResponse nextFollowUp;

    @Schema(description = "The newest Voyra cancel request's status, when the booking has one", example = "OPEN")
    private CancelRequestStatus voyraRequestStatus;

    @Schema(example = "2026-11-22T10:15:00")
    private LocalDateTime cancelledAt;

    @Schema(description = "Kept on cancel: supplier charge plus agency fee", example = "1500.00")
    private BigDecimal cancellationCharges;

    @Schema(example = "8500.00")
    private BigDecimal customerRefundDue;

    @Schema(example = "8500.00")
    private BigDecimal refundedToClient;

    @Schema(example = "9000.00")
    private BigDecimal supplierRefundExpected;

    @Schema(example = "9000.00")
    private BigDecimal supplierRefundReceived;

    @Schema(description = "Accounts cross-checked the cancellation", example = "false")
    private boolean crossChecked;

    @Schema(description = "Flight: the ticket's route (⇄ for a return); null for other types", example = "DEL ⇄ DXB")
    private String sector;

    @Schema(description = "The booking's PNR or reference, else its first voucher's", example = "ABC123")
    private String pnr;

    @Schema(description = "The earliest issued date on the booking's vouchers", example = "2026-11-20")
    private LocalDate issuedOn;

    @Schema(example = "My Partner MMT")
    private String vendorName;

    @Schema(description = "When the refund was marked processed; null while it is still to process")
    private LocalDateTime refundProcessedAt;

    @Schema(example = "Vikram Rao")
    private String refundProcessedBy;
}
