package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agency summary/detail")
public class AgencyResponse {

    @Schema(description = "Agency (tenant) id", example = "4K2N7P")
    private String id;

    @Schema(description = "Agency display name", example = "Global Explorer Travels")
    private String agencyName;

    @Schema(description = "Agency Owner's display name", example = "John Davis")
    private String ownerName;

    @Schema(description = "Agency Owner's login email", example = "owner@globalexplorer.com")
    private String ownerEmail;

    @Schema(description = "Whether the agency can currently log in and operate", example = "true")
    private Boolean isActive;

    @Schema(description = "Number of Agents in this agency", example = "3")
    private Long agentsCount;

    @Schema(description = "Only populated on the single-agency detail endpoint (cross-tenant aggregate read)", example = "52000.00")
    private BigDecimal totalRevenue;

    @Schema(description = "When the agency was onboarded", example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;
}
