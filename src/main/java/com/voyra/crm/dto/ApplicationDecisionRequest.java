package com.voyra.crm.dto;

import com.voyra.crm.enums.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "The platform desk's decision on an agency application (approval has its own endpoint)")
public class ApplicationDecisionRequest {

    @NotNull(message = "Status is required")
    @Schema(description = "UNDER_REVIEW, MORE_INFO or REJECTED", example = "MORE_INFO")
    private ApplicationStatus status;

    @Size(max = 1900, message = "Note must be 1900 characters or fewer")
    @Schema(example = "Upload a clearer PAN card")
    private String note;

    @Size(max = 2000, message = "Rejection reason must be 2000 characters or fewer")
    @Schema(description = "Required when status is REJECTED", example = "PAN card is unreadable")
    private String rejectionReason;
}
