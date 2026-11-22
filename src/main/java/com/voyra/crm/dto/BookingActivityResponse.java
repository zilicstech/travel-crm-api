package com.voyra.crm.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One thing someone did on a booking")
public class BookingActivityResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "CANCELLATION_SCHEDULED")
    private String kind;

    @Schema(example = "Cancellation scheduled for 24 Nov 2026")
    private String text;

    @Schema(example = "Vikram Rao")
    private String byName;

    @Schema(example = "2026-11-22T10:15:00")
    private LocalDateTime at;
}
