package com.voyra.crm.entity;

import com.voyra.crm.enums.PricingProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** A supplier's switches for one product (public schema): searched in every agency's results, and open for booking. */
@Entity
@Table(name = "platform_supplier_product")
@IdClass(PlatformSupplierProduct.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformSupplierProduct {

    @Id
    @Column(name = "credential_id", length = 36)
    private String credentialId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "product", length = 10)
    private PricingProduct product;

    @Column(name = "search_enabled", nullable = false)
    private boolean searchEnabled;

    @Column(name = "booking_enabled", nullable = false)
    private boolean bookingEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String credentialId;
        private PricingProduct product;
    }
}
