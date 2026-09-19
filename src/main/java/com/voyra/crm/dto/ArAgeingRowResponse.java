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
@Schema(description = "One client's outstanding balance bucketed by days overdue, measured from each invoice's due date (falling back to its invoice date when no due date was set).")
public class ArAgeingRowResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(example = "Arjun Mehta")
    private String clientName;

    @Schema(example = "0.00")
    private BigDecimal current;

    @Schema(description = "1-30 days overdue", example = "25120.00")
    private BigDecimal days1To30;

    @Schema(description = "31-60 days overdue", example = "0.00")
    private BigDecimal days31To60;

    @Schema(description = "61-90 days overdue", example = "0.00")
    private BigDecimal days61To90;

    @Schema(description = "90+ days overdue", example = "0.00")
    private BigDecimal days90Plus;

    @Schema(example = "25120.00")
    private BigDecimal totalOutstandingInr;
}
