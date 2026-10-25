package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Re-price a flight offer with the supplier before booking it")
public class FlightReviewRequest {

    @NotBlank
    @Schema(description = "Offer id from the flight search results", example = "mock-indigo-0615-PUBLISHED")
    private String offerId;

    @Min(1)
    @Max(9)
    @Schema(description = "Adults (12+)", example = "2")
    private int adults;

    @Min(0)
    @Max(9)
    @Schema(description = "Children (2-11)", example = "1")
    private int children;

    @Min(0)
    @Max(9)
    @Schema(description = "Infants (under 2)", example = "0")
    private int infants;

    @Schema(description = "Optional CRM client the booking is for", example = "c1d2e3f4-0000-0000-0000-000000000001")
    private String clientId;

    @Schema(description = "Optional CRM lead the booking belongs to", example = "l1d2e3f4-0000-0000-0000-000000000001")
    private String leadId;

    @Schema(description = "Optional lead service tab the booking was launched from", example = "s1d2e3f4-0000-0000-0000-000000000001")
    private String serviceId;

    @Schema(description = "Optional displayed fare the agency saw in the search results; when the review differs, priceChanged is true", example = "4500.00")
    private BigDecimal searchDisplayedFare;
}
