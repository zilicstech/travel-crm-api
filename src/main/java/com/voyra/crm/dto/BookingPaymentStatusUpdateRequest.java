package com.voyra.crm.dto;

import com.voyra.crm.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for updating a booking's payment status")
public class BookingPaymentStatusUpdateRequest {

    @NotNull(message = "Payment status is required")
    @Schema(example = "PAID")
    private PaymentStatus paymentStatus;
}
