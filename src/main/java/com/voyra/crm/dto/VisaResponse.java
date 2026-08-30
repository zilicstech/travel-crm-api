package com.voyra.crm.dto;

import com.voyra.crm.enums.VisaStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A standalone Visa case, distinct from the lightweight tracker embedded on a Lead")
public class VisaResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Denormalized snapshot, live-synced on client rename", example = "Jane Doe")
    private String clientName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String agentName;

    @Schema(description = "Optional traceability link back to the originating lead", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(example = "United Arab Emirates")
    private String country;

    @Schema(example = "Tourist")
    private String visaType;

    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(description = "Server-derived priority ladder: REJECTED > APPROVED > SUBMITTED > APPOINTMENT_SCHEDULED > DOCUMENTS_PENDING", example = "SUBMITTED")
    private VisaStatus status;

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

    @Schema(example = "10 years, multiple entry")
    private String visaValidity;

    @Schema(example = "2036-08-25")
    private LocalDate expiryDate;

    @Schema(example = "2026-08-15")
    private LocalDate applicationDate;
}
