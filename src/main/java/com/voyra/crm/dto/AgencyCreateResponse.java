package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of onboarding a new Agency")
public class AgencyCreateResponse {

    @Schema(description = "Generated Agency (tenant) id", example = "4K2N7P")
    private String id;

    @Schema(description = "Agency display name", example = "Global Explorer Travels")
    private String agencyName;

    @Schema(description = "Agency Owner's display name", example = "John Davis")
    private String ownerName;

    @Schema(description = "Agency Owner's login email", example = "owner@globalexplorer.com")
    private String ownerEmail;
}
