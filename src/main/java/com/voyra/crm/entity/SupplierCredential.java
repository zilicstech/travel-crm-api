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

import java.time.LocalDateTime;

/**
 * One agency's credentials for one external supplier - a tenant-schema table, not a column
 * on the public {@code tenant} row, so a mis-written query cannot cross agencies (blueprint
 * §5.3: tenant isolation is structural, not an authorization check). The encrypted columns
 * are never returned by any controller directly; {@code SupplierCredentialService} always
 * maps through a masked or decrypted DTO depending on the endpoint.
 */
@Entity
@Table(name = "supplier_credential")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SupplierCredential {

    @Id
    @Column(name = "id", length = 36)
    private String id;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

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
