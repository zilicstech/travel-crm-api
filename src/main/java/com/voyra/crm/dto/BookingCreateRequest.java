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
public class BookingCreateRequest {

    @NotBlank(message = "Customer is required")
    private String customerId;

    @Schema(description = "Owner-only: assign the booking to a specific agent. Ignored for the AGENT role (always self).")
    private String agentId;

    @NotNull(message = "Type is required")
    private BookingType type;

    @NotBlank(message = "Destination is required")
    private String destination;

    private String pnr;
    private String ticketNo;
    private String airline;
    private String supplier;
    private LocalDate journeyDate;
    private LocalDate returnDate;
    private String tripType;

    @NotNull(message = "Net cost is required")
    private BigDecimal netCost;

    @NotNull(message = "Selling price is required")
    private BigDecimal sellingPrice;

    @Schema(defaultValue = "PENDING")
    private PaymentStatus paymentStatus;
}
