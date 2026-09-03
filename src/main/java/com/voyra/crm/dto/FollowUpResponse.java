package com.voyra.crm.dto;

import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One follow-up promise, on this lead or on the agent's board")
public class FollowUpResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "Ajay Sharma")
    private String clientName;

    @Schema(example = "Dubai, UAE")
    private String leadDestination;

    @Schema(description = "Null means trip-level", example = "VISA")
    private ServiceType serviceType;

    @Schema(example = "2026-09-20")
    private LocalDate dueDate;

    @Schema(example = "Chase the embassy for appointment confirmation")
    private String note;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String assignedAgentId;

    @Schema(example = "Liam Smith")
    private String assignedAgentName;

    @Schema(example = "OPEN")
    private FollowUpStatus status;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime completedAt;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;
}
