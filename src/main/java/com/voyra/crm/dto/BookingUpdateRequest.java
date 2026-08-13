package com.voyra.crm.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
public class BookingUpdateRequest {

    @Size(max = 20, message = "PNR must be 20 characters or fewer")
    private String pnr;

    @Size(max = 50, message = "Ticket number must be 50 characters or fewer")
    private String ticketNo;

    @Size(max = 100, message = "Airline must be 100 characters or fewer")
    private String airline;

    @Size(max = 150, message = "Supplier must be 150 characters or fewer")
    private String supplier;

    private LocalDate journeyDate;
    private LocalDate returnDate;

    @Size(max = 20, message = "Trip type must be 20 characters or fewer")
    private String tripType;

    @DecimalMin(value = "0.00", message = "Net cost cannot be negative")
    private BigDecimal netCost;

    @DecimalMin(value = "0.00", message = "Selling price cannot be negative")
    private BigDecimal sellingPrice;
}
