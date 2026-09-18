package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One row in the "needs attention" feed - a union of overdue follow-ups, past-deadline
 * bookings, overdue invoices and manually-escalated leads. Read-only aggregation over
 * existing tables; nothing here is stored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One item in the agency's escalation feed")
public class EscalationResponse {

    @Schema(example = "OVERDUE_FOLLOW_UP")
    private String type;

    @Schema(description = "HIGH or MEDIUM", example = "HIGH")
    private String severity;

    @Schema(example = "Chase the embassy for Priya Nair's visa")
    private String title;

    @Schema(example = "Visa / Bangkok, Thailand")
    private String subtitle;

    @Schema(example = "2026-09-10")
    private LocalDate dueDate;

    @Schema(description = "The lead, booking or invoice id this row is about", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String targetId;

    @Schema(description = "LEAD, BOOKING or CLIENT_INVOICE - which id space targetId is in", example = "LEAD")
    private String targetType;
}
