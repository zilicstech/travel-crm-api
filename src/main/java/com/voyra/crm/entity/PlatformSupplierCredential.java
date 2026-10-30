package com.voyra.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
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

import java.time.Instant;

/**
 * Voyra's own account with an external supplier (public schema) - one row per provider (unique since V34), with
 * its commission terms per product in {@link PlatformSupplierProduct}. Every agency's searches run on it. The encrypted columns never leave the service:
 * responses carry a masked key and nothing reveals the plaintext.
 */
@Entity
@Table(name = "platform_supplier_credential")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformSupplierCredential {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private SupplierProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 10)
    private SupplierEnvironment environment;

    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    @Column(name = "api_key_encrypted", nullable = false, length = 1024)
    @JsonIgnore
    private String apiKeyEncrypted;

    @Column(name = "user_id_encrypted", length = 1024)
    @JsonIgnore
    private String userIdEncrypted;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
}
