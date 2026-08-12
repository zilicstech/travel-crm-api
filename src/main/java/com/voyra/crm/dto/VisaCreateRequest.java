package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class VisaCreateRequest {

    @NotBlank(message = "Customer is required")
    private String customerId;

    @Schema(description = "Owner-only: attribute the case to a specific agent. Ignored for the AGENT role (always self).")
    private String agentId;

    @Schema(description = "Optional traceability link back to the originating lead")
    private String leadId;

    @NotBlank(message = "Country is required")
    private String country;

    @NotBlank(message = "Visa type is required")
    private String visaType;

    private String passportNumber;
    private LocalDate applicationDate;
}
