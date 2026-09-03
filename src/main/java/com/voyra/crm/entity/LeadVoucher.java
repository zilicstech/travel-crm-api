package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A PNR or supplier reference for a lead, optionally tied to one service instance - a PNR is a
 * fact about the flight it belongs to, so it lives in that service's tab, not a trip-wide list.
 * {@code fileKey} is opaque, from {@code FileStorageService}, never a filesystem path.
 */
@Entity
@Table(name = "lead_voucher")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadVoucher {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    @Column(name = "service_id", length = 36)
    private String serviceId;

    /** Denormalized snapshot of the service's label at the time this voucher was added. */
    @Column(name = "service_label", length = 200)
    private String serviceLabel;

    @Column(name = "supplier", nullable = false, length = 150)
    private String supplier;

    @Column(name = "reference_number", nullable = false, length = 100)
    private String referenceNumber;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
