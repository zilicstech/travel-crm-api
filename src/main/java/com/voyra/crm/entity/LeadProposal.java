package com.voyra.crm.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import com.voyra.crm.enums.MarkupMode;
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
@SQLDelete(sql = "UPDATE lead_proposal SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class LeadProposal implements com.voyra.crm.models.ForeignPricedRecord {

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

    /** What the agent intended when they set {@link #sellingPrice} - null for a line typed
     *  straight into selling price with no markup calculator involved. Never re-derived from
     *  netCost/sellingPrice after the fact; editing either later can silently drift from it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "markup_mode", length = 10)
    private MarkupMode markupMode;

    @Column(name = "markup_value", precision = 19, scale = 4)
    private BigDecimal markupValue;

    /** What was typed, when the purchase price or a flat markup was in another currency - see util.FxMath.
     *  netCost and markupValue above are always the base-currency figures. */
    @Column(name = "purchase_currency", length = 3)
    private String purchaseCurrency;

    @Column(name = "purchase_price", precision = 14, scale = 2)
    private java.math.BigDecimal purchasePrice;

    @Column(name = "purchase_rate", precision = 18, scale = 6)
    private java.math.BigDecimal purchaseRate;

    @Column(name = "markup_currency", length = 3)
    private String markupCurrency;

    @Column(name = "markup_input", precision = 14, scale = 2)
    private java.math.BigDecimal markupInput;

    @Column(name = "markup_rate", precision = 18, scale = 6)
    private java.math.BigDecimal markupRate;

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
