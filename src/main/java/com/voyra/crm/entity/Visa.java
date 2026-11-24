package com.voyra.crm.entity;

import com.voyra.crm.enums.VisaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    /** The sheet's S.NO, from a tenant sequence. */
    @Generated
    @Column(name = "case_no", insertable = false, updatable = false)
    private Long caseNo;

    /** The traveller (a member of the client); null on cases opened before visa configuration. */
    @Column(name = "member_id", length = 36)
    private String memberId;

    @Column(name = "member_name", length = 150)
    private String memberName;

    @Column(name = "passport_expiry")
    private LocalDate passportExpiry;

    @Column(name = "nationality", length = 60)
    private String nationality;

    /** Configured field id -> value (text, the chosen option, ISO date, "true"/"false"). File fields live in visa_file. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_values", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, String> fieldValues = new LinkedHashMap<>();

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_currency", length = 3)
    private String purchaseCurrency;

    /** In the agency's base currency. Record only: nothing posts it to the books. */
    @Column(name = "selling_price", precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    @Column(name = "vendor_id", length = 36)
    private String vendorId;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Optimistic lock: an in-place edit carries the version it read. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
