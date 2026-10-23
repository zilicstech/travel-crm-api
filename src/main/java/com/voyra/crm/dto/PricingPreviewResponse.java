package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@Schema(description = "What a discount and commission rate would do to the reference fare basket")
public class PricingPreviewResponse {

    @Schema(description = "Lowest Voyra net per 10,000 of gross across the basket", example = "-251.11")
    private BigDecimal worstCaseNetPer10k;

    @Schema(description = "True when the rate loses money on some fare or lets commission exceed earnings", example = "true")
    private boolean requiresSecondApproval;

    @Schema(description = "One line per reference fare")
    private List<BasketLine> basketLines;

    @Data
    @Builder
    public static class BasketLine {

        @Schema(example = "International")
        private String label;

        @Schema(description = "Fare the customer sees", example = "43650.00")
        private BigDecimal displayed;

        @Schema(description = "Voyra's net on this fare", example = "-1130.00")
        private BigDecimal voyraNet;
    }
}
