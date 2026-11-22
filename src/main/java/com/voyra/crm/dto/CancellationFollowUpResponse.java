package com.voyra.crm.dto;

import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.FollowUpStatus;

import java.time.LocalDate;
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
@Schema(description = "A follow-up on a booking's cancellation, or a cancellation scheduled for later")
public class CancellationFollowUpResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "SCHEDULED_CANCEL")
    private CancellationFollowUpKind kind;

    @Schema(example = "2026-11-24")
    private LocalDate dueDate;

    @Schema(example = "Client confirms on Monday; cancel if no reply")
    private String note;

    @Schema(example = "a1b2c3")
    private String assignedAgentId;

    @Schema(example = "Vikram Rao")
    private String assignedAgentName;

    @Schema(example = "OPEN")
    private FollowUpStatus status;

    @Schema(example = "Vikram Rao")
    private String createdByName;

    @Schema(example = "2026-11-22T10:15:00")
    private LocalDateTime createdAt;

    @Schema(example = "Asha Mehta")
    private String doneByName;

    @Schema(example = "2026-11-24T09:00:00")
    private LocalDateTime doneAt;
}
