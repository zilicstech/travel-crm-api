package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@AllArgsConstructor
@Schema(description = "One audited step of a booking's supplier conversation; payload is already redacted")
public class PlatformBookingEventResponse {

    @Schema(description = "Event type", example = "TICKETED")
    private String type;

    @Schema(description = "Redacted event payload")
    private Map<String, Object> payload;

    @Schema(description = "When it happened", example = "2026-10-25T05:02:00Z")
    private Instant createdAt;
}
