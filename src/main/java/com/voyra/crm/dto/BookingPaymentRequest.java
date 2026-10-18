package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Money received from the client for a booking. Settles its invoice, or is held on the client's account until the invoice is generated.")
public class BookingPaymentRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be more than zero")
    @Schema(example = "12000.00")
    private BigDecimal amount;

    @NotNull(message = "Payment mode is required")
    @Schema(example = "UPI")
    private PaymentMode paymentMode;

    @NotNull(message = "Date is required")
    @Schema(example = "2026-10-17")
    private LocalDate receivedOn;

    @Schema(description = "UTR, cheque no. or similar", example = "UTR123456")
    private String reference;
}
