package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Structurally incapable of carrying internal fields (netCost, profit, agent identity) -
 * only what a customer needs to recognise the trip and leave a rating. See
 * PublicFeedbackTenantService for where this is built, matching PublicProposalTenantService's
 * pricing-safe-by-construction reasoning.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Public, unauthenticated view of a booking for leaving feedback")
public class PublicFeedbackResponse {

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(description = "True once this booking already has a submitted rating", example = "false")
    private boolean alreadySubmitted;

    @Schema(example = "5")
    private Integer rating;

    @Schema(example = "Wonderful trip, the itinerary was spot on!")
    private String comment;
}
