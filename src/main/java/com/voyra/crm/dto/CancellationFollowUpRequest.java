package com.voyra.crm.dto;

import com.voyra.crm.enums.CancellationFollowUpKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A follow-up, or a cancellation scheduled for later: a date, or a number of days from today")
public class CancellationFollowUpRequest {

    @NotNull(message = "Pick what to schedule")
    @Schema(example = "SCHEDULED_CANCEL")
    private CancellationFollowUpKind kind;

    @Schema(description = "The day it is due; give this or inDays", example = "2026-11-24")
    private LocalDate dueDate;

    @Min(value = 1, message = "Pick 1 to 30 days")
    @Max(value = 30, message = "Pick 1 to 30 days")
    @Schema(description = "Due this many days from today; give this or dueDate", example = "2")
    private Integer inDays;

    @Size(max = 500, message = "Write at most 500 characters")
    @Schema(example = "Client confirms on Monday; cancel if no reply")
    private String note;

    @Schema(description = "Who follows it up: the owner (agency id) or an agent; default the caller", example = "a1b2c3")
    private String assignedAgentId;
}
