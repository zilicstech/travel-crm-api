package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
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
@Schema(description = "One entry in a lead's activity stream. Written by the server on every "
        + "state change and never client-writable, which is what makes it an audit trail "
        + "rather than another notes field.")
public class LeadTimelineResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Null for a lead-level event; set for one scoped to a service instance",
            example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(example = "STATUS_CHANGED")
    private LeadTimelineEventType eventType;

    @Schema(description = "Populated only for STATUS_CHANGED events", example = "CONTACTED")
    private LeadStatus fromStatus;

    @Schema(description = "Populated only for STATUS_CHANGED events", example = "PROPOSAL_SENT")
    private LeadStatus toStatus;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String actorAgentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String actorName;

    @Schema(example = "Status changed from CONTACTED to PROPOSAL_SENT")
    private String description;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;
}
