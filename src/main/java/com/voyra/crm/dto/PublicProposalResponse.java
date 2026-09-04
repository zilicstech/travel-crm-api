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
 * email, budget, lostReason, clientId.
 *
 * <p>The traveller manifest is absent for the same reason and then some: a named-traveller
 * list would put passport numbers and dates of birth behind a link that needs no login. Only
 * the aggregate {@code guestCount} crosses this boundary - never {@code members},
 * {@code memberName}, {@code passportNumber}, or {@code dob}. A controller-slice test asserts
 * those keys are absent from the raw JSON; extend it rather than relaxing it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Public, unauthenticated view of a proposal - pricing-safe by construction")
public class PublicProposalResponse {

    @Schema(example = "Jane Doe")
    private String clientName;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @Schema(description = "Total headcount only - the named traveller manifest is never exposed here", example = "4")
    private Integer guestCount;

    @Schema(description = "The proposal's line items, pricing-safe by construction")
    private List<PublicProposalItemResponse> items;

    @Schema(description = "Sum of items' sellingPrice - net cost and margin are never exposed", example = "52000.00")
    private BigDecimal grandTotal;

    @Schema(description = "When true, option selections are frozen - the customer cannot pick or "
            + "confirm further until an agent unlocks it")
    private boolean locked;
}
