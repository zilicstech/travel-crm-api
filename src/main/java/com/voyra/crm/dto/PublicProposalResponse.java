package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The ONLY thing the unauthenticated public proposal page ever sees. Never derived from the
 * Lead entity by a generic mapper - every field here is deliberately selected. Absent on
 * purpose: netCost, margin, status, priority, source, assignedTo, notes, visaTracker, phone,
 * email, budget, lostReason, customerId.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Public, unauthenticated view of a proposal - pricing-safe by construction")
public class PublicProposalResponse {

    @Schema(example = "Jane Doe")
    private String customerName;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @Schema(description = "adults + children + infants", example = "2")
    private Integer guestCount;

    private List<PublicProposalItemResponse> items;

    @Schema(description = "Sum of items' sellingPrice - net cost and margin are never exposed", example = "52000.00")
    private BigDecimal grandTotal;
}
