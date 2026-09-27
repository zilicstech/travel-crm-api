package com.voyra.crm.dto;

import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ServiceType;
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
@Schema(description = "The agency's default markup for one service type - pre-fills a new proposal line")
public class MarkupDefaultResponse {

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "PERCENT")
    private MarkupMode mode;

    @Schema(example = "5.00")
    private BigDecimal value;
}
