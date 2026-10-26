package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The agency's basic report on its Voyra bookings. Hand-built like PlatformBookingResponse: no Voyra figures, net, base or supplier ids. */
@Data
@Builder
@Schema(description = "Bookings made through Voyra in a date range, with upcoming trips and bookings needing attention")
public class PlatformBookingSummaryResponse {

    @Schema(description = "First day counted (IST)", example = "2026-10-01")
    private LocalDate from;

    @Schema(description = "Last day counted (IST)", example = "2026-10-27")
    private LocalDate to;

    @Schema(description = "Bookings created in the range, excluding ones never confirmed (reviewing or dropped)", example = "12")
    private int bookings;

    @Schema(description = "Of those, ticketed (hotels: confirmed)", example = "10")
    private int ticketed;

    @Schema(description = "Of those, cancelled", example = "1")
    private int cancelled;

    @Schema(description = "What the wallet was charged for the ticketed ones", example = "54000.00")
    private BigDecimal sales;

    @Schema(description = "What the agency earns on the ticketed ones, after TDS", example = "1140.00")
    private BigDecimal earned;

    @Schema(description = "The same figures per product; FLIGHT and HOTEL are always present")
    private List<ProductSummary> byProduct;

    @Schema(description = "Up to 5 ticketed bookings travelling today or later, soonest first (whatever the range)")
    private List<PlatformBookingResponse> upcoming;

    @Schema(description = "Up to 5 bookings waiting on the supplier or cancelling, newest first (whatever the range)")
    private List<PlatformBookingResponse> attention;

    @Schema(description = "One product's figures in the range")
    public record ProductSummary(
            @Schema(description = "Product", example = "FLIGHT") PricingProduct product,
            @Schema(description = "Bookings created in the range, excluding reviewing and dropped", example = "8") int bookings,
            @Schema(description = "What the wallet was charged for the ticketed ones", example = "40000.00") BigDecimal sales,
            @Schema(description = "What the agency earns on the ticketed ones, after TDS", example = "900.00") BigDecimal earned) {
    }
}
