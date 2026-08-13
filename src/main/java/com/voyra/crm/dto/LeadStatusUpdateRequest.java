package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for updating a Lead's status")
public class LeadStatusUpdateRequest {

    @NotNull(message = "Status is required")
    @Schema(example = "NEGOTIATING")
    private LeadStatus status;

    @Schema(description = "Required when status is LOST (business rule: lost leads require a mandatory reason)")
    private String lostReason;
}
