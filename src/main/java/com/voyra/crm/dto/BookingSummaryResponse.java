package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lightweight booking shape embedded in the client detail view - see BookingResponse for the full shape. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lightweight booking summary embedded in the client detail view")
public class BookingSummaryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "FLIGHT")
    private BookingType type;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(example = "CONFIRMED")
    private BookingStatus bookingStatus;
}
