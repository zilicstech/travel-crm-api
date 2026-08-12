package com.voyra.crm.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeadFollowUpUpdateRequest {

    @NotNull(message = "Follow-up date is required")
    private LocalDate followUpDate;
}
