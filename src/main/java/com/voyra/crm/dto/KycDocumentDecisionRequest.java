package com.voyra.crm.dto;

import com.voyra.crm.enums.KycDocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Verify or reject one KYC document")
public class KycDocumentDecisionRequest {

    @NotNull(message = "Status is required")
    @Schema(example = "VERIFIED")
    private KycDocumentStatus status;

    @Size(max = 1000, message = "Note must be 1000 characters or fewer")
    @Schema(example = "Name matches the application")
    private String note;
}
