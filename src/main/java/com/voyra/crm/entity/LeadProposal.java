package com.voyra.crm.entity;

import com.voyra.crm.enums.ProposalItemType;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A quotation line on a lead's proposal.
 *
 * <p>{@code netCost} is internal and must never reach the customer-facing proposal surface -
 * the public DTO is hand-built to make that structurally impossible. Margin is always
 * recomputed from these two columns by MarginCalculator and never read from a stored value.
 */
@Entity
@Table(name = "lead_proposal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadProposal {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    /** Null means a trip-level charge; set means this line belongs to one service instance. */
    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "service_label", length = 200)
    private String serviceLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProposalItemType type;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "supplier", length = 150)
    private String supplier;

    @Column(name = "net_cost", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal netCost = BigDecimal.ZERO;

    @Column(name = "selling_price", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sellingPrice = BigDecimal.ZERO;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    /** Null = plain add-on, always counted. Shared value = mutually-exclusive alternatives. */
    @Column(name = "option_group", length = 36)
    private String optionGroup;

    /** Within an option group, which line currently counts toward the total. */
    @Column(name = "is_selected", nullable = false)
    @Builder.Default
    private boolean isSelected = false;

    /** Who made the selection: AGENT or CUSTOMER. Null until a selection has been made. */
    @Column(name = "selected_by", length = 20)
    private String selectedBy;

    @Column(name = "selected_at")
    private LocalDateTime selectedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
