package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Money moved on a cancelled booking - a refund paid to the customer, or one received from the supplier")
public class RefundSettlementRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be more than zero")
    @Schema(example = "7500.00")
    private BigDecimal amount;

    @NotNull(message = "Date is required")
    @Schema(example = "2026-10-16")
    private LocalDate paidOn;

    @NotNull(message = "Payment mode is required")
    @Schema(example = "UPI")
    private PaymentMode paymentMode;

    @Schema(description = "UTR, cheque no. or similar", example = "UTR123456")
    private String reference;
}
