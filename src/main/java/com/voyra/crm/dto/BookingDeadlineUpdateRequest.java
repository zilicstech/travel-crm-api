package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for setting a booking's ticketing/cancellation deadlines")
public class BookingDeadlineUpdateRequest {

    @Schema(description = "Ticketing time limit - when the fare must be ticketed by", example = "2026-09-18")
    private LocalDate ticketingDeadline;

    @Schema(description = "Last date to cancel without penalty", example = "2026-09-15")
    private LocalDate cancellationDeadline;

    @Schema(example = "Fare holds ticket only until 6 PM IST")
    private String deadlineNote;
}
