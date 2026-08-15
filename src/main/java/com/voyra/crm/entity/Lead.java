package com.voyra.crm.entity;

import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "lead")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Lead {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "country_code", length = 6)
    private String countryCode;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "destination", nullable = false, length = 150)
    private String destination;

    @Column(name = "travel_date_from")
    private LocalDate travelDateFrom;

    @Column(name = "travel_date_to")
    private LocalDate travelDateTo;

    @Column(name = "budget", length = 50)
    private String budget;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeadStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LeadSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private LeadPriority priority;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", columnDefinition = "text[]")
    @Builder.Default
    private List<LeadCategory> categories = List.of();

    @Column(name = "assigned_to", nullable = false, length = 36)
    private String assignedTo;

    @Column(name = "assigned_agent_name", nullable = false, length = 150)
    private String assignedAgentName;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "lost_reason", length = 255)
    private String lostReason;

    @Column(name = "adults", nullable = false)
    @Builder.Default
    private Integer adults = 1;

    @Column(name = "children", nullable = false)
    @Builder.Default
    private Integer children = 0;

    @Column(name = "infants", nullable = false)
    @Builder.Default
    private Integer infants = 0;

    @Column(name = "special_requirements")
    private String specialRequirements;

    @Column(name = "passport_collected")
    private Boolean passportCollected;

    @Column(name = "photos_collected")
    private Boolean photosCollected;

    @Column(name = "forms_filled")
    private Boolean formsFilled;

    @Column(name = "submitted_to_embassy")
    private Boolean submittedToEmbassy;

    @Column(name = "approved")
    private Boolean approved;

    @Column(name = "public_proposal_token", length = 32)
    private String publicProposalToken;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
