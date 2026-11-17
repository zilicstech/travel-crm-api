package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "The search offer whose fare rules are wanted")
public class FlightFareRulesRequest {

    @NotBlank(message = "Offer is required")
    @Schema(description = "offerId of a fare from a flight search", example = "s1:off-1")
    private String offerId;
}
