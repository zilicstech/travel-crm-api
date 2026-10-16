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

    @Schema(description = "True when a tax invoice exists - a credit note is issued automatically")
    private boolean invoiced;
}
