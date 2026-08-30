package com.voyra.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.ServiceType;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * AGENT login. Stored in the public schema (not the tenant schema) with a tenant_id column,
 * because login must resolve the account before the tenant schema/search_path is known -
 * same shape as the blueprint's reference AppUser entity (§8.4).
 */
@Entity
@Table(name = "agent")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Agent {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * Display-only, kept for existing rows. {@link #manageableServices} is what actually
     * decides which leads and services this agent may touch - the two are independent and
     * are not kept in sync (see BACKEND_BLUEPRINT.md decision in the phase1 plan).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "department", length = 30)
    private AgentDepartment department;

    @Column(name = "password", nullable = false)
    @JsonIgnore
    private String password;

    /**
     * Which service types this agent may work on, on any lead in the agency, whoever owns it.
     * The whole authorization model for the lead/service surface runs on this array - see
     * docs/LLD_LEAD_MANAGEMENT.md §8.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "manageable_services", columnDefinition = "text[]")
    @Builder.Default
    private List<ServiceType> manageableServices = List.of();

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal commissionRate = new BigDecimal("5.00");

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
