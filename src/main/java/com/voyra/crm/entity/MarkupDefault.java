package com.voyra.crm.entity;

import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ServiceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The agency's own default markup for one {@link ServiceType} - what pre-fills a new proposal
 * line so an agent isn't guessing company policy on every quote. One row per type, upserted in
 * place (unlike {@code TaxRateConfig}, this carries no history - a policy change simply
 * overwrites the row; it was never a promise about what an already-issued quote would honour).
 */
@Entity
@Table(name = "markup_default")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MarkupDefault {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 20)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private MarkupMode mode;

    @Column(name = "value", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal value = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
