package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Platform P&L over bookings ticketed in a date range (IST days, both ends inclusive)")
public class PnlResponse {

    @Schema(description = "First day", example = "2026-10-01")
    private LocalDate from;

    @Schema(description = "Last day", example = "2026-10-31")
    private LocalDate to;

    @Schema(description = "Totals over every booking in range")
    private PnlRow totals;

    @Schema(description = "One row per agency, by agency name")
    private List<PnlRow> byAgency;

    @Schema(description = "One row per product")
    private List<PnlRow> byProduct;
}
