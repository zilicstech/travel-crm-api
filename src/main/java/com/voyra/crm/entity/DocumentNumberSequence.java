package com.voyra.crm.entity;

import com.voyra.crm.enums.DocumentKind;
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

import java.time.LocalDateTime;

/**
 * One row per (documentKind, financialYear). {@link com.voyra.crm.service.DocumentNumberService}
 * increments {@link #lastValue} with a raw {@code UPDATE ... RETURNING} statement, not through
 * this entity, so the row lock is held for the whole issuing transaction. The entity and its
 * repository exist for the seed-row insert and for tests, not for the hot increment path.
 */
@Entity
@Table(name = "document_number_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class DocumentNumberSequence {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_kind", nullable = false, length = 20)
    private DocumentKind documentKind;

    @Column(name = "financial_year", nullable = false, length = 9)
    private String financialYear;

    @Column(name = "prefix", nullable = false, length = 12)
    private String prefix;

    @Column(name = "last_value", nullable = false)
    @Builder.Default
    private Long lastValue = 0L;

    @Column(name = "padding", nullable = false)
    @Builder.Default
    private Integer padding = 4;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
