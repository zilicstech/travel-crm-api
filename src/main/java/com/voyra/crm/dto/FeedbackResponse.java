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
@Schema(description = "A customer's submitted rating/comment for one of their bookings")
public class FeedbackResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "Dubai, UAE")
    private String bookingDestination;

    @Schema(example = "5")
    private Integer rating;

    @Schema(example = "Wonderful trip, the itinerary was spot on!")
    private String comment;

    @Schema(example = "2026-09-18T14:30:00")
    private LocalDateTime submittedAt;
}
