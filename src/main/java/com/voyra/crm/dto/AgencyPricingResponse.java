package com.voyra.crm.dto;

import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@Schema(description = "One agency pricing row")
public class AgencyPricingResponse {

    @Schema(example = "8c3e1f7a-0b5d-4c1e-9f0a-2b6d4e8a1c3f")
    private String id;

    @Schema(description = "The agency (tenant) id", example = "t-1")
    private String tenantId;

    @Schema(example = "FLIGHT")
    private PricingProduct product;

    @Schema(example = "3.00")
    private BigDecimal discountPct;

    @Schema(example = "4.00")
    private BigDecimal commissionPct;

    @Schema(example = "false")
    private boolean allowCommissionAboveEarned;

    @Schema(example = "2026-11-01T00:00:00Z")
    private Instant effectiveFrom;

    @Schema(description = "Lowest Voyra net per 10,000 of gross across the reference basket", example = "-251.11")
    private BigDecimal worstCaseNetPer10k;

    @Schema(description = "True only on rates created before rates were approved at once that waited for a second admin", example = "false")
    private boolean requiresSecondApproval;

    @Schema(description = "Platform admin user id", example = "u-1")
    private String createdBy;

    @Schema(example = "2026-10-23T09:30:00Z")
    private Instant createdAt;

    @Schema(description = "Null until approved", example = "u-2")
    private String approvedBy;

    @Schema(description = "Null until approved", example = "2026-10-23T10:00:00Z")
    private Instant approvedAt;

    @Schema(description = "Set when a never-approved rate was withdrawn; such a rate never applies", example = "2026-10-23T10:00:00Z")
    private Instant withdrawnAt;

    @Schema(description = "True for the one row resolve() returns now for this product", example = "true")
    private boolean inForce;
}
