package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LeadStatusUpdateRequest {

    @NotNull(message = "Status is required")
    private LeadStatus status;

    @Schema(description = "Required when status is LOST (business rule: lost leads require a mandatory reason)")
    private String lostReason;
}
