package com.voyra.crm.dto;

import com.voyra.crm.enums.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@Schema(description = "An agency application as the platform desk sees it")
public class AgencyApplicationResponse {

    @Schema(example = "6f1c2a4e-3b8d-4c1a-9e2f-7d5b0a1c3e9f")
    private String id;

    @Schema(example = "SUBMITTED")
    private ApplicationStatus status;

    @Schema(example = "Sky Trips")
    private String agencyName;

    @Schema(example = "Sky Trips Private Limited")
    private String legalName;

    @Schema(example = "Sky Trips Holidays")
    private String tradeName;

    @Schema(example = "ABCDE1234F")
    private String pan;

    @Schema(example = "29ABCDE1234F1Z5")
    private String gstin;

    @Schema(example = "U63040KA2020PTC123456")
    private String cin;

    @Schema(example = "12 MG Road")
    private String address;

    @Schema(example = "Bengaluru")
    private String city;

    @Schema(example = "29")
    private String stateCode;

    @Schema(example = "560001")
    private String pincode;

    @Schema(example = "Asha Rao")
    private String ownerName;

    @Schema(example = "asha@skytrips.in")
    private String ownerEmail;

    @Schema(example = "+91 98765 43210")
    private String ownerPhone;

    @Schema(example = "50-100 bookings")
    private String expectedMonthlyVolume;

    @Schema(example = "INR")
    private String baseCurrencyCode;

    @Schema(example = "2026-10-23T09:30:00Z")
    private Instant consentAt;

    @Schema(example = "2026-10-23T09:45:00Z")
    private Instant submittedAt;

    @Schema(description = "Platform admin id of the last reviewer")
    private String reviewedBy;

    @Schema(example = "2026-10-24T11:00:00Z")
    private Instant reviewedAt;

    @Schema(example = "Called the owner; GST certificate pending")
    private String reviewNote;

    @Schema(example = "PAN card is unreadable")
    private String rejectionReason;

    @Schema(description = "The agency created on approval")
    private String tenantId;

    @Schema(example = "2026-10-23T09:30:00Z")
    private Instant createdAt;

    @Schema(example = "2026-10-23T09:45:00Z")
    private Instant updatedAt;

    @Schema(description = "Uploaded KYC documents, oldest first")
    private List<KycDocumentResponse> documents;
}
