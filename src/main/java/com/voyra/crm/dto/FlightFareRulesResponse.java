package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Fare rules of one search offer")
public class FlightFareRulesResponse {

    @Schema(description = "The supplier's fare rules as it sent them (JSON or plain text); null when it published none")
    private String fareRules;
}
