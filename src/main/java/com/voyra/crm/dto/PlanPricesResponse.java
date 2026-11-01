package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Monthly INR prices (excl. GST) of the paid plans and one extra staff seat")
public class PlanPricesResponse {

    @JsonProperty("LITE")
    @Schema(example = "2999")
    private BigDecimal lite;

    @JsonProperty("SUITE")
    @Schema(example = "4999")
    private BigDecimal suite;

    @JsonProperty("STAFF_SEATS")
    @Schema(example = "399")
    private BigDecimal staffSeats;
}
