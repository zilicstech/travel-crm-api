package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Request body for updating an Agency's name or owner details")
public class AgencyUpdateRequest {

    @Size(max = 150, message = "Agency name must be 150 characters or fewer")
    @Schema(example = "Global Explorer Travels")
    private String agencyName;

    @Size(max = 150, message = "Owner name must be 150 characters or fewer")
    @Schema(example = "John Davis")
    private String ownerName;

    @Email(message = "Owner email must be a valid email address")
    @Size(max = 150, message = "Owner email must be 150 characters or fewer")
    @Schema(example = "owner@globalexplorer.com")
    private String ownerEmail;
}
