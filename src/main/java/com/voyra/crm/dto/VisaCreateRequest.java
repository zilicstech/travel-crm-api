package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for creating a standalone Visa case")
public class VisaCreateRequest {

    @NotBlank(message = "Customer is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String customerId;

    @Schema(description = "Owner-only: attribute the case to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String agentId;

    @Schema(description = "Optional traceability link back to the originating lead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @NotBlank(message = "Country is required")
    @Schema(example = "United Arab Emirates")
    private String country;

    @NotBlank(message = "Visa type is required")
    @Schema(example = "Tourist")
    private String visaType;

    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(example = "2026-08-15")
    private LocalDate applicationDate;
}
