package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightSortOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "A flight search plus how to page, sort and filter its result")
public class FlightSearchPageRequest {

    @Valid
    @NotNull(message = "Search query is required")
    private FlightSearchQuery query;

    @Min(value = 1, message = "Page starts at 1")
    @Schema(example = "1")
    private int page = 1;

    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 50, message = "Page size must be at most 50")
    @Schema(example = "20")
    private int pageSize = 20;

    @NotNull(message = "Sort order is required")
    private FlightSortOrder sort = FlightSortOrder.PRICE;

    @Schema(description = "Show flights with at most this many stops; omit for any", example = "0")
    private Integer maxStops;

    @Schema(description = "Only these airlines (by name); omit or empty for all")
    private List<String> airlines;

    @Schema(description = "Only refundable fares")
    private boolean refundableOnly;

    @Schema(description = "True on a fresh Search click: re-queries the supplier instead of reusing "
            + "the cached result. False for page, sort and filter changes.")
    private boolean refresh;
}
