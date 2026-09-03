package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One leg of a flight service, stored in {@code lead_service.flight_sectors} (JSONB) and used
 * verbatim as the API shape - there is no separate storage representation, since a sector is
 * nothing more than these three fields. A round trip stores leg 2 as a mirror of leg 1 so the
 * two can never disagree, matching the frontend's {@code sectors} array.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One flight leg. Airport codes are upper-cased and trimmed on write; a "
        + "service may hold at most 8 sectors.")
public class FlightSectorDto {

    @Schema(example = "BOM")
    private String from;

    @Schema(example = "BKK")
    private String to;

    @Schema(example = "2026-10-01")
    private LocalDate date;
}
