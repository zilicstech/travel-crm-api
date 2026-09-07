package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierCredentialResponse;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.dto.SupplierCredentialUpsertRequest;
import com.voyra.crm.entity.SupplierCredential;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.repository.SupplierCredentialRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PasswordEncryptionUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Per-agency credentials for an external supplier search integration. Stored in the tenant
 * schema (blueprint §5.3 - tenant isolation is structural, not an authorization check), so a
 * mis-written query cannot leak one agency's key to another. Encrypted with the same
 * AES-256-GCM key as agent password retrieval ({@code util.PasswordEncryptionUtil}) rather
 * than a second key - one key to rotate, one property to get right.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierCredentialService {

    private final SupplierCredentialRepository supplierCredentialRepository;

    @Value("${app.security.password-encryption-key}")
    private String encryptionKey;

    @Transactional
    public SupplierCredentialResponse upsert(SupplierCredentialUpsertRequest request) {
        SupplierCredential credential = supplierCredentialRepository.findByProvider(request.getProvider())
                .orElseGet(() -> SupplierCredential.builder()
                        .id(UniqueIdResolver.resolve(supplierCredentialRepository::existsById))
                        .provider(request.getProvider())
                        .build());

        credential.setEnvironment(request.getEnvironment());
        credential.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            credential.setApiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey(), encryptionKey));
        } else if (credential.getApiKeyEncrypted() == null) {
            throw new IllegalArgumentException("API key is required");
        }
        if (request.getUserId() != null && !request.getUserId().isBlank()) {
            credential.setUserIdEncrypted(PasswordEncryptionUtil.encrypt(request.getUserId(), encryptionKey));
        }
        if (request.getIsActive() != null) {
            credential.setIsActive(request.getIsActive());
        }
        credential.setUpdatedAt(LocalDateTime.now());
        credential.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());

        supplierCredentialRepository.save(credential);
        log.info("Supplier credential saved: provider={}, environment={}, by={}",
                credential.getProvider(), credential.getEnvironment(), SecurityContextUtil.getAuditInfo());
        return toResponse(credential);
    }

    @Transactional(readOnly = true)
    public SupplierCredentialResponse get(SupplierProvider provider) {
        SupplierCredential credential = findByProvider(provider);
        return toResponse(credential);
    }

    /** Owner-only retrieval path - callers must already be behind an AGENCY_OWNER-role guard and every call is logged. */
    @Transactional(readOnly = true)
    public SupplierCredentialRevealResponse reveal(SupplierProvider provider) {
        var principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (!principal.isAgencyOwner()) {
            throw new AccessDeniedException("Only the agency owner can reveal supplier credentials");
        }
        SupplierCredential credential = findByProvider(provider);
        log.info("Supplier credential revealed: provider={}, by={}", provider, SecurityContextUtil.getAuditInfo());
        return SupplierCredentialRevealResponse.builder()
                .provider(credential.getProvider())
                .environment(credential.getEnvironment())
                .baseUrl(credential.getBaseUrl())
                .apiKey(PasswordEncryptionUtil.decrypt(credential.getApiKeyEncrypted(), encryptionKey))
                .userId(credential.getUserIdEncrypted() != null
                        ? PasswordEncryptionUtil.decrypt(credential.getUserIdEncrypted(), encryptionKey)
                        : null)
                .build();
    }

    /**
     * The lookup a search provider calls on every request - resolved fresh each time rather
     * than cached on the provider bean, which is a singleton shared by every tenant; caching
     * credentials there would serve one agency's key to all of them. Returns before any HTTP
     * call is made (blueprint §8.6 - no HTTP inside a transaction).
     */
    @Transactional(readOnly = true)
    public SupplierCredentialRevealResponse resolveActive(SupplierProvider provider) {
        SupplierCredential credential = supplierCredentialRepository.findByProvider(provider)
                .filter(SupplierCredential::getIsActive)
                .orElseThrow(() -> new IllegalStateException(
                        provider + " is not configured for this agency. An owner can add credentials in Settings."));
        return SupplierCredentialRevealResponse.builder()
                .provider(credential.getProvider())
                .environment(credential.getEnvironment())
                .baseUrl(credential.getBaseUrl())
                .apiKey(PasswordEncryptionUtil.decrypt(credential.getApiKeyEncrypted(), encryptionKey))
                .userId(credential.getUserIdEncrypted() != null
                        ? PasswordEncryptionUtil.decrypt(credential.getUserIdEncrypted(), encryptionKey)
                        : null)
                .build();
    }

    private SupplierCredential findByProvider(SupplierProvider provider) {
        return supplierCredentialRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException(provider + " credentials are not configured yet"));
    }

    private SupplierCredentialResponse toResponse(SupplierCredential c) {
        return SupplierCredentialResponse.builder()
                .id(c.getId())
                .provider(c.getProvider())
                .environment(c.getEnvironment())
                .baseUrl(c.getBaseUrl())
                .maskedApiKey(mask(PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey)))
                .isActive(c.getIsActive())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String mask(String plainText) {
        if (plainText.length() <= 4) {
            return "••••••";
        }
        return "••••••" + plainText.substring(plainText.length() - 4);
    }
}
