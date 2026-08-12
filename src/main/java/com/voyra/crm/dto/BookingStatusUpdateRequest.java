package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BookingStatusUpdateRequest {

    @NotNull(message = "Booking status is required")
    private BookingStatus bookingStatus;

    @Schema(description = "Required when bookingStatus is CANCELLED")
    private String cancelReason;

    @Schema(description = "e.g. 'Refunded ₹62,000' - set when a cancellation has been refunded")
    private String refundStatus;
}
