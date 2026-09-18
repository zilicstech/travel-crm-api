package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One day-scoped item on the agency-wide calendar - a union of trip departures/returns,
 * follow-up due dates, invoice due dates, booking deadlines and visa appointments. Read-only
 * aggregation over existing tables; nothing here is stored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One event on the agency-wide calendar")
public class CalendarEventResponse {

    @Schema(example = "TRIP_DEPARTURE")
    private String type;

    @Schema(example = "2026-09-20")
    private LocalDate date;

    @Schema(example = "Priya Nair departs for Bangkok, Thailand")
    private String title;

    @Schema(example = "Booking #f47ac10b")
    private String subtitle;

    @Schema(description = "HIGH or MEDIUM", example = "MEDIUM")
    private String severity;

    @Schema(description = "The lead, booking, invoice or visa id this event is about", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String targetId;

    @Schema(description = "LEAD, BOOKING, CLIENT_INVOICE or VISA - which id space targetId is in", example = "BOOKING")
    private String targetType;
}
