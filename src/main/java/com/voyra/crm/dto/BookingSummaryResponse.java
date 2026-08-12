package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lightweight booking shape embedded in the Customer detail view - see BookingResponse for the full shape. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingSummaryResponse {

    private String id;
    private BookingType type;
    private String destination;
    private LocalDate journeyDate;
    private BigDecimal sellingPrice;
    private BookingStatus bookingStatus;
}
