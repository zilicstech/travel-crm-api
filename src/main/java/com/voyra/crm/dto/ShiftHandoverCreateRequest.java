package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "A note left for the next shift - not rostering/clock-in")
public class ShiftHandoverCreateRequest {

    @Schema(description = "Leave null to address the whole team", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String toAgentId;

    @NotBlank(message = "Summary is required")
    @Size(max = 2000, message = "Summary must be 2000 characters or fewer")
    @Schema(example = "Priya Nair's visa docs are with the embassy, follow up Monday if no update.")
    private String summary;

    @Schema(description = "Lead ids worth flagging for the next shift")
    private List<String> pinnedLeadIds;

    @Schema(description = "Booking ids worth flagging for the next shift")
    private List<String> pinnedBookingIds;

    @Schema(description = "Defaults to now if omitted", example = "2026-09-18T18:00:00")
    private LocalDateTime shiftEndedAt;
}
