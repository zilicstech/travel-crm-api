package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@Schema(description = "What a proposed rule change would reach")
public class PricingRuleImpactResponse {

    @Schema(description = "Agencies on the rule", example = "4")
    private long agencyCount;

    @Schema(description = "Agencies whose discount and commission pair would be loss-making on the reference basket")
    private List<LossMakingAgency> lossMaking;

    @Data
    @Builder
    public static class LossMakingAgency {

        @Schema(example = "t-1")
        private String tenantId;

        @Schema(example = "Sky Trips")
        private String agencyName;

        @Schema(description = "Voyra's worst case per 10,000 of fare", example = "-120.00")
        private BigDecimal worstCaseNetPer10k;
    }
}
