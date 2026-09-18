package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A note left for the next shift")
public class ShiftHandoverResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String fromAgentId;

    @Schema(example = "Liam Fernandes")
    private String fromAgentName;

    @Schema(description = "Null means addressed to the whole team", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String toAgentId;

    @Schema(example = "Maya Rao")
    private String toAgentName;

    @Schema(example = "Priya Nair's visa docs are with the embassy, follow up Monday if no update.")
    private String summary;

    @Schema(description = "Lead ids flagged for the next shift")
    private List<String> pinnedLeadIds;

    @Schema(description = "Booking ids flagged for the next shift")
    private List<String> pinnedBookingIds;

    @Schema(example = "2026-09-18T18:00:00")
    private LocalDateTime shiftEndedAt;

    @Schema(example = "2026-09-19T09:05:00")
    private LocalDateTime acknowledgedAt;

    @Schema(example = "Maya Rao")
    private String acknowledgedByName;

    @Schema(example = "2026-09-18T18:02:00")
    private LocalDateTime createdAt;
}
