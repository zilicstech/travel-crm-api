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
import java.math.BigDecimal;
import java.time.Instant;

/** A supplier's commission terms for one product (public schema): what Voyra expects to earn, used to price agency fares. */
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

    @Column(name = "commission_estimate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionEstimatePct;

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
