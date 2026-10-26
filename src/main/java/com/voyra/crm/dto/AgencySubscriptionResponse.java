package com.voyra.crm.dto;

import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
@Schema(description = "The agency's own subscription: plan, seats, period and invoices")
public class AgencySubscriptionResponse {

    @Schema(description = "STARTER, LITE, CUSTOM or SUITE")
    private SubscriptionPlan plan;

    @Schema(description = "SKUs usable now")
    private Set<Sku> skus;

    @Schema(description = "Active staff logins", example = "4")
    private long seatsUsed;

    @Schema(description = "Staff logins allowed", example = "5")
    private int seatsTotal;

    @Schema(description = "TRIAL, ACTIVE, PAST_DUE, READ_ONLY or CANCELLED")
    private SubscriptionStatus status;

    @Schema(description = "MONTHLY or ANNUAL")
    private BillingCycle billingCycle;

    @Schema(description = "Current period start", nullable = true)
    private Instant periodStart;

    @Schema(description = "Current period end", nullable = true)
    private Instant periodEnd;

    @Schema(description = "Trial end", nullable = true)
    private Instant trialEndsAt;

    @Schema(description = "True when writes are refused until the agency renews")
    private boolean readOnly;

    @Schema(description = "True when Voyra currently waives the fee")
    private boolean waived;

    @Schema(description = "Friendly label for a waiver, e.g. Founding agency", nullable = true)
    private String waivedLabel;

    @Schema(description = "Issued, paid and void invoices, newest first")
    private List<SubscriptionInvoiceResponse> invoices;
}
