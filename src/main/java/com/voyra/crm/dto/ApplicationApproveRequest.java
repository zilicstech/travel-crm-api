package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Approve an agency application - creates the agency and its owner login")
public class ApplicationApproveRequest {

    @NotBlank(message = "Owner password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Schema(description = "Login password for the Agency Owner, chosen by the Platform Admin and handed to the owner", example = "SecurePass123")
    private String ownerPassword;
}
