package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSupplierCredentialRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PasswordEncryptionUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Voyra's one platform-level account per supplier. Write-only: the key goes in encrypted (the same AES-256-GCM
 * key as password retrieval) and comes out masked; the only plaintext path is {@link #resolveActive}, which the
 * search providers call and no controller exposes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformSupplierCredentialService {

    static final String ENTITY_TYPE = "SUPPLIER_CREDENTIAL";

    private final PlatformSupplierCredentialRepository repository;
    private final PlatformAuditService platformAuditService;

    @Value("${app.security.password-encryption-key}")
    private String encryptionKey;

    @Transactional
    public PlatformSupplierCredentialResponse upsert(PlatformSupplierCredentialRequest request) {
        List<PlatformSupplierCredential> rows = repository.findByProviderOrderByUpdatedAtDesc(request.getProvider());
        PlatformSupplierCredential credential = rows.isEmpty()
                ? PlatformSupplierCredential.builder()
                        .id(UniqueIdResolver.resolve(repository::existsById))
                        .provider(request.getProvider())
                        .build()
                : rows.get(0);

        boolean keyChanged = request.getApiKey() != null && !request.getApiKey().isBlank();
        if (keyChanged) {
            credential.setApiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey(), encryptionKey));
        } else if (credential.getApiKeyEncrypted() == null) {
            throw new IllegalArgumentException("API key is required");
        }
        if (request.getUserId() != null && !request.getUserId().isBlank()) {
            credential.setUserIdEncrypted(PasswordEncryptionUtil.encrypt(request.getUserId(), encryptionKey));
        }
        credential.setEnvironment(request.getEnvironment());
        credential.setBaseUrl(request.getBaseUrl());
        if (request.getIsActive() != null) {
            credential.setIsActive(request.getIsActive());
        }
        if (credential.getIsActive()) {
            // Flushed first: the partial unique index on (provider) WHERE is_active would reject two active rows.
            List<PlatformSupplierCredential> others = rows.stream()
                    .filter(r -> r != credential && Boolean.TRUE.equals(r.getIsActive()))
                    .toList();
            others.forEach(r -> r.setIsActive(false));
            if (!others.isEmpty()) {
                repository.saveAllAndFlush(others);
            }
        }
        credential.setUpdatedAt(Instant.now());
        credential.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        repository.save(credential);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("credentialId", credential.getId());
        detail.put("environment", credential.getEnvironment().name());
        detail.put("baseUrl", credential.getBaseUrl());
        detail.put("isActive", credential.getIsActive());
        detail.put("keyChanged", keyChanged);
        platformAuditService.record(PlatformAuditAction.CREDENTIAL_SET, ENTITY_TYPE, credential.getProvider().name(), detail);
        log.info("Platform supplier credential saved: provider={}, environment={}, active={}, by={}",
                credential.getProvider(), credential.getEnvironment(), credential.getIsActive(), SecurityContextUtil.getAuditInfo());
        return toResponse(credential);
    }

    @Transactional(readOnly = true)
    public PlatformSupplierCredentialResponse get(SupplierProvider provider) {
        return repository.findByProviderOrderByUpdatedAtDesc(provider).stream().findFirst()
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException(provider + " credentials are not configured yet"));
    }

    /** The only way a search provider gets a key - resolved per call, so a key change applies to the next search. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolveActive(SupplierProvider provider) {
        PlatformSupplierCredential c = repository.findByProviderAndIsActiveTrue(provider)
                .orElseThrow(() -> new IllegalStateException("Tripjack is not configured on the platform"));
        return new ResolvedCredential(c.getProvider(), c.getEnvironment(), c.getBaseUrl(),
                PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey),
                c.getUserIdEncrypted() != null ? PasswordEncryptionUtil.decrypt(c.getUserIdEncrypted(), encryptionKey) : null);
    }

    @Transactional(readOnly = true)
    public boolean hasActive(SupplierProvider provider) {
        return repository.findByProviderAndIsActiveTrue(provider).isPresent();
    }

    private PlatformSupplierCredentialResponse toResponse(PlatformSupplierCredential c) {
        String key = PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey);
        return PlatformSupplierCredentialResponse.builder()
                .id(c.getId())
                .provider(c.getProvider())
                .environment(c.getEnvironment())
                .baseUrl(c.getBaseUrl())
                .maskedApiKey(key.length() <= 4 ? "••••••" : "••••••" + key.substring(key.length() - 4))
                .isActive(c.getIsActive())
                .updatedAt(c.getUpdatedAt())
                .updatedBy(c.getUpdatedBy())
                .build();
    }
}
