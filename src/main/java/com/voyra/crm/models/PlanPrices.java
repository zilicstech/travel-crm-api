package com.voyra.crm.models;

import com.voyra.crm.enums.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** The monthly INR prices of the paid plans and of one extra staff seat, read from the sku_prices setting. */
@Schema(description = "Monthly INR prices of the paid plans and the extra staff seat")
public record PlanPrices(
        @Schema(example = "2999") BigDecimal lite,
        @Schema(example = "4999") BigDecimal suite,
        @Schema(example = "399") BigDecimal staffSeats) {

    /** Monthly price of a plan; Basic (STARTER) is free. */
    public BigDecimal monthly(SubscriptionPlan plan) {
        return switch (plan) {
            case STARTER -> BigDecimal.ZERO;
            case LITE -> lite;
            case SUITE -> suite;
        };
    }
}
