package com.voyra.crm.dto;

import com.voyra.crm.enums.VisaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisaResponse {

    private String id;
    private String customerId;
    private String customerName;
    private String agentId;
    private String agentName;
    private String leadId;
    private String country;
    private String visaType;
    private String passportNumber;
    private VisaStatus status;
    private Boolean passportCollected;
    private Boolean photosCollected;
    private Boolean formsFilled;
    private LocalDate appointmentDate;
    private Boolean biometricsDone;
    private Boolean submittedToEmbassy;
    private Boolean approved;
    private Boolean rejected;
    private Boolean passportReturned;
    private String visaValidity;
    private LocalDate expiryDate;
    private LocalDate applicationDate;
}
