package com.voyra.crm.dto;

import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.WaivedReason;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
@Schema(description = "An agency's subscription as the platform admin sees it")
public class SubscriptionAdminResponse {

    @Schema(description = "STARTER, CUSTOM or SUITE")
    private SubscriptionPlan plan;

    @Schema(description = "SKUs usable now")
    private Set<Sku> skus;

    @Schema(description = "Extra seats stored on the subscription (SUITE: on top of 5; CUSTOM: all)", example = "3")
    private int extraSeats;

    @Schema(description = "Staff logins allowed now", example = "8")
    private int seatsTotal;

    @Schema(description = "Active staff logins", example = "4")
    private long seatsUsed;

    @Schema(description = "TRIAL, ACTIVE, PAST_DUE, READ_ONLY or CANCELLED")
    private SubscriptionStatus status;

    @Schema(description = "MONTHLY or ANNUAL")
    private BillingCycle billingCycle;

    @Schema(description = "Current period start", nullable = true)
    private Instant periodStart;

    @Schema(description = "Current period end, also the end of a waiver", nullable = true)
    private Instant periodEnd;

    @Schema(description = "Trial end", nullable = true)
    private Instant trialEndsAt;

    @Schema(description = "Why the fee is waived; null = not waived", nullable = true)
    private WaivedReason waivedReason;

    @Schema(description = "True when writes are refused")
    private boolean readOnly;
}
