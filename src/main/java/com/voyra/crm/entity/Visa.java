package com.voyra.crm.entity;

import com.voyra.crm.enums.VisaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "visa")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Visa {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    @Column(name = "agent_name", nullable = false, length = 150)
    private String agentName;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "country", nullable = false, length = 100)
    private String country;

    @Column(name = "visa_type", nullable = false, length = 50)
    private String visaType;

    @Column(name = "passport_number", length = 20)
    private String passportNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private VisaStatus status;

    @Column(name = "passport_collected", nullable = false)
    @Builder.Default
    private Boolean passportCollected = false;

    @Column(name = "photos_collected", nullable = false)
    @Builder.Default
    private Boolean photosCollected = false;

    @Column(name = "forms_filled", nullable = false)
    @Builder.Default
    private Boolean formsFilled = false;

    @Column(name = "appointment_date")
    private LocalDate appointmentDate;

    @Column(name = "biometrics_done", nullable = false)
    @Builder.Default
    private Boolean biometricsDone = false;

    @Column(name = "submitted_to_embassy", nullable = false)
    @Builder.Default
    private Boolean submittedToEmbassy = false;

    @Column(name = "approved", nullable = false)
    @Builder.Default
    private Boolean approved = false;

    @Column(name = "rejected", nullable = false)
    @Builder.Default
    private Boolean rejected = false;

    @Column(name = "passport_returned", nullable = false)
    @Builder.Default
    private Boolean passportReturned = false;

    @Column(name = "visa_validity", length = 50)
    private String visaValidity;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
        if (applicationDate == null) {
            applicationDate = LocalDate.now();
        }
    }
}
