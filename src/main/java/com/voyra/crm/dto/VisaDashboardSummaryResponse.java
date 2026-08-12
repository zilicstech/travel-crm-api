package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisaDashboardSummaryResponse {

    private long total;
    private long documentsPending;
    private long appointmentScheduled;
    private long submitted;
    private long approved;
    private long rejected;
    private long passportReturned;
    private long passportCollectionPending;
}
