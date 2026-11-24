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

    /** When the case was opened (agency time); shown as the case's opening date and time. */
    @Schema(example = "2026-08-15T10:30:00")
    private java.time.LocalDateTime createdAt;

    @Schema(description = "The sheet's S.NO", example = "42")
    private Long caseNo;

    @Schema(description = "The traveller; null on cases opened before visa configuration", example = "m-1")
    private String memberId;

    @Schema(description = "Denormalized snapshot, live-synced on member rename", example = "Jane Doe")
    private String memberName;

    @Schema(example = "2031-04-30")
    private LocalDate passportExpiry;

    @Schema(example = "Indian")
    private String nationality;

    @Schema(description = "Configured field id -> value", example = "{\"f1\": \"RECVD\"}")
    private java.util.Map<String, String> values;

    @Schema(description = "Files held for File fields")
    private java.util.List<VisaFileResponse> files;

    @Schema(description = "Detail only: labels of removed fields this application still holds, by field id", example = "{\"f7\": \"OLD FIELD\"}")
    private java.util.Map<String, String> removedFields;

    @Schema(example = "120.00")
    private java.math.BigDecimal purchasePrice;

    @Schema(example = "USD")
    private String purchaseCurrency;

    @Schema(description = "In the agency's base currency", example = "12500.00")
    private java.math.BigDecimal sellingPrice;

    @Schema(example = "v-1")
    private String vendorId;

    @Schema(description = "Denormalized snapshot, live-synced on vendor rename", example = "Atlas Visas")
    private String vendorName;

    @Schema(example = "Express processing")
    private String remarks;

    @Schema(description = "Send it back with an edit; a newer one on the server is 409 VISA_CHANGED", example = "3")
    private Long version;
}
