package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Owner's Visa dashboard KPIs, counted from each case's server-derived status")
public class VisaDashboardSummaryResponse {

    @Schema(description = "Total visa cases", example = "5")
    private long total;

    @Schema(example = "2")
    private long documentsPending;

    @Schema(example = "1")
    private long appointmentScheduled;

    @Schema(example = "1")
    private long submitted;

    @Schema(example = "1")
    private long approved;

    @Schema(example = "0")
    private long rejected;

    @Schema(description = "Approved cases where the passport has been returned to the customer", example = "0")
    private long passportReturned;

    @Schema(description = "Cases where the customer's passport has not yet been collected", example = "2")
    private long passportCollectionPending;
}
