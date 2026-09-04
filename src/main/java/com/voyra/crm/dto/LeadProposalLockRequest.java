package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for locking/unlocking a lead's proposal")
public class LeadProposalLockRequest {

    @NotNull(message = "locked is required")
    @Schema(description = "true freezes the whole proposal; false reopens it", example = "false")
    private Boolean locked;
}
