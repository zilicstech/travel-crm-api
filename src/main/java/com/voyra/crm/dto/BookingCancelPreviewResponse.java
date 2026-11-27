package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@Schema(description = "What cancelling would mean for the money, before anything is written")
public class BookingCancelPreviewResponse {

    @Schema(description = "Received from the customer against this booking, net of refunds", example = "10000.00")
    private BigDecimal customerPaid;

    @Schema(description = "Paid to the supplier on this booking's bills", example = "8000.00")
    private BigDecimal paidToSupplier;

    @Schema(description = "What the customer would get back", example = "7500.00")
    private BigDecimal customerRefundDue;

    @Schema(description = "What the supplier would owe the agency back", example = "6000.00")
    private BigDecimal supplierRefundExpected;

    @Schema(description = "What the agency would still owe the supplier: the part of their cancellation charge not already paid", example = "20000.00")
    private BigDecimal supplierChargeOwed;

    @Schema(description = "Before any invoice: the \"Cancellation charges\" invoice cancelling raises for what is kept, no GST (null when nothing is kept or the booking is invoiced)", example = "4680.00")
    private BigDecimal chargesInvoiceTotal;

    @Schema(description = "True when a tax invoice exists - a credit note is issued automatically")
    private boolean invoiced;

    @Schema(description = "On the booking's value (information only): its selling price", example = "31800.00")
    private BigDecimal bookingValue;

    @Schema(description = "On the booking's value: selling price less every charge, never below 0", example = "25200.00")
    private BigDecimal refundOnValue;

    @Schema(description = "On the booking's value: its purchase price", example = "29150.00")
    private BigDecimal purchaseValue;

    @Schema(description = "On the booking's value: purchase price less the airline and supplier charges, never below 0", example = "24076.00")
    private BigDecimal expectedOnPurchase;
}
