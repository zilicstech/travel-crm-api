package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Request body for onboarding a new Agency (tenant)")
public class AgencyCreateRequest {

    @NotBlank(message = "Agency name is required")
    @Schema(example = "Global Explorer Travels")
    private String agencyName;

    @NotBlank(message = "Owner name is required")
    @Schema(example = "John Davis")
    private String ownerName;

    @NotBlank(message = "Owner email is required")
    @Email(message = "Owner email must be a valid email address")
    @Schema(example = "owner@globalexplorer.com")
    private String ownerEmail;

    @NotBlank(message = "Owner password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Schema(description = "Login password for the Agency Owner, chosen by the Platform Admin and handed to the owner", example = "SecurePass123")
    private String ownerPassword;
}
