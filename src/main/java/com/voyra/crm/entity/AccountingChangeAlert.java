package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One itinerary-relevant edit made to a booking that already has a live invoice (or is
 * CONFIRMED) against it - FRD US-ACC-1.1's "change notification in the accounting workbench
 * requiring accountant review." Separate from {@code audit_log}: the audit log is an immutable
 * history of every change to every booking; this table is a work queue an accountant clears by
 * acknowledging each row. Raised in the same transaction as the audit-log write it accompanies,
 * never in place of it.
 */
@Entity
@Table(name = "accounting_change_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class AccountingChangeAlert {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "field_name", nullable = false, length = 60)
    private String fieldName;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "raised_by", length = 36)
    private String raisedBy;

    @Column(name = "acknowledged", nullable = false)
    @Builder.Default
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 36)
    private String acknowledgedBy;
}
