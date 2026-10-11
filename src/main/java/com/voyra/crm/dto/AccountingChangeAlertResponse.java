package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One itinerary-relevant edit to an already-confirmed/invoiced booking, awaiting accountant review")
public class AccountingChangeAlertResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "b-004512")
    private String bookingId;

    @Schema(example = "Rakesh Verma Family / Goa")
    private String bookingLabel;

    @Schema(example = "journeyDate")
    private String fieldName;

    @Schema(example = "2026-11-10")
    private String oldValue;

    @Schema(example = "2026-11-17")
    private String newValue;

    @Schema(example = "2026-10-01T12:30:00")
    private LocalDateTime raisedAt;

    @Schema(example = "agent-002")
    private String raisedBy;

    @Schema(example = "false")
    private Boolean acknowledged;

    @Schema(example = "2026-10-01T15:00:00")
    private LocalDateTime acknowledgedAt;

    @Schema(example = "owner-001")
    private String acknowledgedBy;
}
