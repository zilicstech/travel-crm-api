package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for updating a Lead's next follow-up date")
public class LeadFollowUpUpdateRequest {

    @NotNull(message = "Follow-up date is required")
    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;
}
