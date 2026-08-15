package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Request body for creating a Booking")
public class BookingCreateRequest {

    @NotBlank(message = "Customer is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String customerId;

    @Schema(description = "Owner-only: assign the booking to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String agentId;

    @NotNull(message = "Type is required")
    @Schema(example = "FLIGHT")
    private BookingType type;

    @NotBlank(message = "Destination is required")
    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(description = "Airline PNR/booking reference", example = "ABCXYZ")
    private String pnr;

    @Schema(description = "E-ticket number", example = "0987654321")
    private String ticketNo;

    @Schema(example = "Emirates")
    private String airline;

    @Schema(description = "Supplier/vendor this was booked through", example = "Cleartrip")
    private String supplier;

    @Schema(description = "Outbound travel date", example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(description = "Return travel date, if applicable", example = "2026-09-22")
    private LocalDate returnDate;

    @Schema(description = "One-way or round-trip", example = "Round Trip")
    private String tripType;

    @NotNull(message = "Net cost is required")
    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @NotNull(message = "Selling price is required")
    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(defaultValue = "PENDING", example = "PENDING")
    private PaymentStatus paymentStatus;
}
