package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
}
