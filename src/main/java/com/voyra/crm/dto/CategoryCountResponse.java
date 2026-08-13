package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Generic "group by X, count" shape - reused for lead-pipeline, lead-source, booking-type, and funnel breakdowns. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One category and its count, for a distribution or funnel report")
public class CategoryCountResponse {

    @Schema(description = "The category name (a status, source, or type enum value)", example = "NEW")
    private String category;

    @Schema(description = "Number of records in this category", example = "12")
    private long count;
}
