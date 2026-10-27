package com.voyra.crm.models;

import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

/** What an agency may use right now, resolved from its subscription by EntitlementService. */
@Schema(description = "What the agency's subscription unlocks right now")
public record Entitlements(
        @Schema(example = "SUITE") SubscriptionPlan plan,
        @Schema(example = "TRIAL") SubscriptionStatus status,
        @Schema(description = "CRM SKUs usable now; empty = Starter (portal only)") Set<Sku> skus,
        @Schema(description = "Staff logins allowed (the owner is not a seat)", example = "5") int seats,
        @Schema(description = "True when reads work but writes are refused (trial or waiver over, or unpaid)") boolean readOnly,
        @Schema(description = "When the trial or waiver ends; null = no expiry, or already ended") Instant accessEndsAt) {
}
