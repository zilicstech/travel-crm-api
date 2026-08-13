package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** Patch semantics - only non-null fields are applied. Status is always server-recalculated after any change. */
@Data
@Schema(description = "Request body for updating a Visa's checklist. Only non-null fields are applied; status is always server-recalculated.")
public class VisaChecklistUpdateRequest {

    @Schema(example = "true")
    private Boolean passportCollected;

    @Schema(example = "true")
    private Boolean photosCollected;

    @Schema(example = "true")
    private Boolean formsFilled;

    @Schema(example = "2026-08-25")
    private LocalDate appointmentDate;

    @Schema(example = "true")
    private Boolean biometricsDone;

    @Schema(example = "true")
    private Boolean submittedToEmbassy;

    @Schema(example = "false")
    private Boolean approved;

    @Schema(example = "false")
    private Boolean rejected;

    @Schema(example = "false")
    private Boolean passportReturned;

    @Size(max = 50, message = "Visa validity must be 50 characters or fewer")
    @Schema(example = "10 years, multiple entry")
    private String visaValidity;

    @Schema(example = "2036-08-25")
    private LocalDate expiryDate;
}
