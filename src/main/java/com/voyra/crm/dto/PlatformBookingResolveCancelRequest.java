package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Settle a cancellation the supplier has not answered")
public class PlatformBookingResolveCancelRequest {

    @NotNull
    @Schema(description = "True when the airline cancelled the ticket, false to put the booking back to ticketed", example = "true")
    private Boolean cancelled;

    @PositiveOrZero
    @Schema(description = "Refund the supplier gave back; blank when not known", example = "4000.00")
    private BigDecimal refund;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Why, for the audit log", example = "Airline confirmed by email")
    private String note;
}
