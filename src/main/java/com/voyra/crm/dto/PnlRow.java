package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Platform P&L figures for one group of bookings (all, one agency or one product)")
public class PnlRow {

    @Schema(description = "Group key: ALL, the agency id or the product", example = "FLIGHT")
    private String key;

    @Schema(description = "Group label: agency name or product", example = "Acme Travel")
    private String label;

    @Schema(description = "Bookings ticketed in the period, including ones cancelled since", example = "42")
    private int bookings;

    @Schema(description = "Of those, cancelled", example = "3")
    private int cancellations;

    @Schema(description = "GMV: what agencies paid (sum of displayed fares)", example = "410000.00")
    private BigDecimal gmv;

    @Schema(description = "Supplier gross fares", example = "425000.00")
    private BigDecimal gross;

    @Schema(description = "Discounts given to agencies (gross - displayed)", example = "15000.00")
    private BigDecimal discountsGiven;

    @Schema(description = "Commission credited to agencies before TDS", example = "4200.00")
    private BigDecimal commissionPaid;

    @Schema(description = "TDS withheld on that commission", example = "84.00")
    private BigDecimal tdsWithheld;

    @Schema(description = "Voyra earned still on the estimate (or API figure)", example = "3000.00")
    private BigDecimal earnedEstimate;

    @Schema(description = "Voyra earned confirmed by a Tripjack statement", example = "9000.00")
    private BigDecimal earnedStatement;

    @Schema(description = "Earned commission reversed on cancellations", example = "600.00")
    private BigDecimal reversals;

    @Schema(description = "Voyra net (earned - discounts - commission, after cancellations)", example = "-6400.00")
    private BigDecimal voyraNet;
}
