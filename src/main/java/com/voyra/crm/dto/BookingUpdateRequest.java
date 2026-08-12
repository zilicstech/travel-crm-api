package com.voyra.crm.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
public class BookingUpdateRequest {

    private String pnr;
    private String ticketNo;
    private String airline;
    private String supplier;
    private LocalDate journeyDate;
    private LocalDate returnDate;
    private String tripType;
    private BigDecimal netCost;
    private BigDecimal sellingPrice;
}
