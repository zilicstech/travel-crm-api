package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
@Schema(description = "One plain-language step in a booking's history")
public class BookingTimelineLine {

    @Schema(description = "When it happened", example = "2026-10-25T05:00:00Z")
    private Instant at;

    @Schema(description = "What happened, in plain words", example = "Ticket issued")
    private String text;
}
