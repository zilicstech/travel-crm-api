package com.voyra.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An Agency. Doubles as the Agency Owner's login record (id is also the tenant schema key) -
 * there is no separate "owner" table, mirroring the blueprint's Tenant-is-the-admin-login
 * pattern (§3.6).
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Tenant {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "agency_name", nullable = false, length = 150)
    private String agencyName;

    @Column(name = "owner_name", nullable = false, length = 150)
    private String ownerName;

    @Column(name = "owner_email", nullable = false, length = 150)
    private String ownerEmail;

    @Column(name = "password", nullable = false)
    @JsonIgnore
    private String password;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "gst_number", length = 30)
    private String gstNumber;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "default_commission", precision = 5, scale = 2)
    private BigDecimal defaultCommission;

    @Column(name = "logo_key", length = 500)
    private String logoKey;

    @Column(name = "state_code", length = 2)
    private String stateCode;

    @Column(name = "base_currency_code", nullable = false, length = 3)
    @Builder.Default
    private String baseCurrencyCode = "INR";

    @Column(name = "default_sac_code", length = 10)
    private String defaultSacCode;

    @Column(name = "invoice_terms", length = 2000)
    private String invoiceTerms;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "bank_account_name", length = 200)
    private String bankAccountName;

    @Column(name = "bank_account_number", length = 40)
    private String bankAccountNumber;

    @Column(name = "bank_ifsc_code", length = 20)
    private String bankIfscCode;

    @Column(name = "bank_branch", length = 200)
    private String bankBranch;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
