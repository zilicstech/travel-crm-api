package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A Booking, with profit always server-computed")
public class BookingResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String customerId;

    @Schema(description = "Denormalized snapshot, live-synced on customer rename", example = "Jane Doe")
    private String customerName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String agentName;

    @Schema(example = "FLIGHT")
    private BookingType type;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "ABCXYZ")
    private String pnr;

    @Schema(example = "0987654321")
    private String ticketNo;

    @Schema(example = "Emirates")
    private String airline;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(example = "2026-09-22")
    private LocalDate returnDate;

    @Schema(example = "Round Trip")
    private String tripType;

    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Always sellingPrice minus netCost, recomputed server-side", example = "10000.00")
    private BigDecimal profit;

    @Schema(example = "CONFIRMED")
    private BookingStatus bookingStatus;

    @Schema(example = "PAID")
    private PaymentStatus paymentStatus;

    @Schema(example = "2026-08-13")
    private LocalDate bookingDate;

    @Schema(description = "Required when bookingStatus is CANCELLED")
    private String cancelReason;

    @Schema(description = "e.g. 'Refunded ₹62,000' - set when a cancellation has been refunded")
    private String refundStatus;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;
}
