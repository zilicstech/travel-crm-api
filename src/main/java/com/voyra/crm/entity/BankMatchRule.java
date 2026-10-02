package com.voyra.crm.entity;

import com.voyra.crm.enums.BankMatchField;
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

/**
 * Rule 4.3.3 - applies only to a bank_transaction left unmatched by both tiers, in {@link
 * #priority} order (lower runs first). {@link #autoPost} true posts rule 16 (a bank charge or
 * interest line) directly against {@link #targetAccountCode}; false only pre-fills the
 * categorisation for a human to confirm.
 */
@Entity
@Table(name = "bank_match_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankMatchRule {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 20)
    private BankMatchField matchField;

    @Column(name = "pattern", nullable = false, length = 200)
    private String pattern;

    @Column(name = "target_account_code", nullable = false, length = 10)
    private String targetAccountCode;

    @Column(name = "auto_post", nullable = false)
    @Builder.Default
    private Boolean autoPost = false;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
