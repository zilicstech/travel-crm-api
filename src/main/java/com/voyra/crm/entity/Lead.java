package com.voyra.crm.entity;

import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadPriority;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An enquiry, always belonging to exactly one {@link Client}.
 *
 * <p>Contact details are not duplicated here - they resolve through the client's primary
 * member. {@code clientName} and {@code clientType} are denormalized snapshots so the leads
 * list is a single flat SELECT; they are re-synced in bulk when the client is renamed.
 *
 * <p>{@code kidAges} holds each child's age as quoted by the client. The infant count is
 * derived from it rather than stored, which is also why there is no {@code infants} column.
 * {@code totalTravellers} is stored for list-query performance but recomputed from
 * {@code adults + kids} on every write - the same treatment booking profit gets, and for the
 * same reason: a stored aggregate that is never recomputed eventually lies.
 *
 * <p>Who actually travels is the {@link LeadMember} manifest, not these counts. The counts are
 * what the client said on the phone; the manifest is who is on the ticket.
 */
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

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 10)
    private ClientType clientType;

    @Column(name = "destination", nullable = false, length = 150)
    private String destination;

    @Column(name = "travel_date_from")
    private LocalDate travelDateFrom;

    @Column(name = "travel_date_to")
    private LocalDate travelDateTo;

    @Column(name = "adults", nullable = false)
    @Builder.Default
    private Integer adults = 1;

    @Column(name = "kids", nullable = false)
    @Builder.Default
    private Integer kids = 0;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "kid_ages", columnDefinition = "integer[]")
    @Builder.Default
    private List<Integer> kidAges = List.of();

    @Column(name = "total_travellers", nullable = false)
    @Builder.Default
    private Integer totalTravellers = 1;

    @Column(name = "lead_description")
    private String leadDescription;

    @Column(name = "preferences")
    private String preferences;

    /** Column is {@code current_status} per the agreed schema; "current" is redundant in Java. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 20)
    private LeadStatus status;

    /**
     * Agency-configurable free text, validated at write time against {@code agency_setting}
     * (kind = LEAD_SOURCE) rather than a fixed Java enum - the Settings screen lets an agency
     * add its own sources, and a hardcoded enum could never reflect that.
     */
    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private LeadPriority priority;

    /** Same reasoning as {@link #source} - validated against agency_setting (TRAVEL_CATEGORY), not an enum. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", columnDefinition = "text[]")
    @Builder.Default
    private List<String> categories = List.of();

    @Column(name = "budget", length = 50)
    private String budget;

    @Column(name = "assigned_to", nullable = false, length = 36)
    private String assignedTo;

    @Column(name = "assigned_agent_name", nullable = false, length = 150)
    private String assignedAgentName;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "lost_reason", length = 255)
    private String lostReason;

    @Column(name = "public_proposal_token", length = 32)
    private String publicProposalToken;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    /** One remark for the whole trip - see the LLD's rationale for retiring per-service notes. */
    @Column(name = "special_notes")
    private String specialNotes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "travel_preferences", columnDefinition = "text[]")
    @Builder.Default
    private List<String> travelPreferences = List.of();

    /** Roll-up of every non-cancelled service's proposal lines - recomputed on every proposal or status write. */
    @Column(name = "quoted_net_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal quotedNetTotal = BigDecimal.ZERO;

    @Column(name = "quoted_selling_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal quotedSellingTotal = BigDecimal.ZERO;

    /** Count of OPEN lead_follow_up rows - drives the overdue badge without a join on every list render. */
    @Column(name = "open_follow_ups", nullable = false)
    @Builder.Default
    private Integer openFollowUps = 0;

    @Column(name = "service_count", nullable = false)
    @Builder.Default
    private Integer serviceCount = 0;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
