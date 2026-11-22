package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Move an open follow-up, reassign it or change its note; absent fields stay as they are")
public class CancellationFollowUpUpdateRequest {

    @Schema(example = "2026-11-26")
    private LocalDate dueDate;

    @Schema(example = "a1b2c3")
    private String assignedAgentId;

    @Size(max = 500, message = "Write at most 500 characters")
    @Schema(example = "Client asked for two more days")
    private String note;
}
