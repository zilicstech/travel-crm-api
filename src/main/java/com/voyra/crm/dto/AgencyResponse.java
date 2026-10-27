package com.voyra.crm.dto;

import com.voyra.crm.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Schema(description = "False only when DEACTIVATED - kept for existing readers; prefer status", example = "true")
    private Boolean isActive;

    @Schema(description = "Agency liveness - SUSPENDED signs in read-only, DEACTIVATED cannot sign in", example = "ACTIVE")
    private TenantStatus status;

    @Schema(description = "Number of Agents in this agency", example = "3")
    private Long agentsCount;

    @Schema(description = "Only populated on the single-agency detail endpoint (cross-tenant aggregate read)", example = "52000.00")
    private BigDecimal totalRevenue;

    @Schema(description = "When the agency was onboarded", example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;

    @Schema(description = "ISO 4217 code the agency's books are kept in - set at creation, never changed", example = "INR")
    private String baseCurrencyCode;

    @Schema(description = "Agency PAN from KYC - null for agencies created without an application", example = "ABCDE1234F")
    private String pan;

    @Schema(description = "Company CIN from KYC, when the agency gave one", example = "U63040KA2020PTC123456")
    private String cin;

    @Schema(description = "When a platform admin approved the agency's application - null for directly created agencies",
            example = "2026-10-24T09:15:22Z")
    private Instant approvedAt;

    @Schema(description = "The application this agency was approved from - detail endpoint only, null otherwise",
            example = "6f1c2b9e-3a4d-4c1e-9b7a-2d5e8f0a1b3c")
    private String applicationId;
}
