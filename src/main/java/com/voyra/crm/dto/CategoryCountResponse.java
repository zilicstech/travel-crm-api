package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Generic "group by X, count" shape - reused for lead-pipeline, lead-source, booking-type, and funnel breakdowns. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCountResponse {

    private String category;
    private long count;
}
