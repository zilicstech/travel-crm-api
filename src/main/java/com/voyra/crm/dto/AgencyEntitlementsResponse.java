package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.models.Entitlements;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The entitlements fields at the top level, plus the agency's status - the frontend's suspended banner reads it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "What the agency's subscription unlocks right now, and whether the agency is suspended")
public class AgencyEntitlementsResponse {

    @JsonUnwrapped
    @Schema(description = "Unwrapped: plan, status, skus, seats, readOnly, accessEndsAt sit at the top level")
    private Entitlements entitlements;

    @Schema(description = "ACTIVE, or SUSPENDED when Voyra has made the agency read-only (writes get 423)", example = "ACTIVE")
    private TenantStatus agencyStatus;
}
