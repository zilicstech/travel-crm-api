package com.voyra.crm.entity;

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

import java.time.LocalDateTime;
import java.util.List;

/**
 * A real supplier party record - name, contact, GSTIN - replacing the name-only
 * {@code agency_setting} rows of kind SUPPLIER. {@code Booking.supplier},
 * {@code LeadProposal.supplier} and {@code SupplierInvoice.supplierName} keep referencing a
 * vendor by name (no FK, per §8.4); see {@link com.voyra.crm.service.VendorService} for the
 * rename re-sync that keeps those snapshots live.
 */
@Entity
@Table(name = "vendor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Vendor {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** Same shape as agent.manageable_services - no join table, per §8.4. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "service_types", columnDefinition = "text[]")
    @Builder.Default
    private List<ServiceType> serviceTypes = List.of();

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    @Column(name = "notes", length = 1000)
    private String notes;

    /** Free text ("10% commission, net 30") - a typed rate would need a currency/unit this
     *  business does not model yet. */
    @Column(name = "default_rate_note", length = 255)
    private String defaultRateNote;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
