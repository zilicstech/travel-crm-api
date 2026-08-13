package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for updating a Booking's details")
public class BookingUpdateRequest {

    @Size(max = 20, message = "PNR must be 20 characters or fewer")
    @Schema(example = "ABCXYZ")
    private String pnr;

    @Size(max = 50, message = "Ticket number must be 50 characters or fewer")
    @Schema(example = "0987654321")
    private String ticketNo;

    @Size(max = 100, message = "Airline must be 100 characters or fewer")
    @Schema(example = "Emirates")
    private String airline;

    @Size(max = 150, message = "Supplier must be 150 characters or fewer")
    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(example = "2026-09-22")
    private LocalDate returnDate;

    @Size(max = 20, message = "Trip type must be 20 characters or fewer")
    @Schema(example = "Round Trip")
    private String tripType;

    @DecimalMin(value = "0.00", message = "Net cost cannot be negative")
    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @DecimalMin(value = "0.00", message = "Selling price cannot be negative")
    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;
}
