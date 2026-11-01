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
 * One of Voyra's accounts with an external supplier (public schema). Several rows may share an integration (name is
 * unique); {@link PlatformSupplierProduct} says which products each row is searched and booked for. The encrypted columns
 * never leave the service: responses carry a masked key and nothing reveals the plaintext. {@code isActive} is read only
 * by the old single-supplier providers and goes with them.
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
