package com.voyra.crm.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** Patch semantics - only non-null fields are applied. Status is always server-recalculated after any change. */
@Data
public class VisaChecklistUpdateRequest {

    private Boolean passportCollected;
    private Boolean photosCollected;
    private Boolean formsFilled;
    private LocalDate appointmentDate;
    private Boolean biometricsDone;
    private Boolean submittedToEmbassy;
    private Boolean approved;
    private Boolean rejected;
    private Boolean passportReturned;

    @Size(max = 50, message = "Visa validity must be 50 characters or fewer")
    private String visaValidity;

    private LocalDate expiryDate;
}
