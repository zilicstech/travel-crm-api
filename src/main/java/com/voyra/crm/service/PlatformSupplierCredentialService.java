package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSupplierCredentialRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.SupplierEnvironment;
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

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** The only hosts the platform key may be sent to - a free-text URL would let the key be pointed anywhere. */
    static final Map<SupplierEnvironment, Set<String>> ALLOWED_HOSTS = Map.of(
            SupplierEnvironment.UAT, Set.of("apitest.tripjack.com", "apitest-hms.tripjack.com"),
            SupplierEnvironment.PROD, Set.of("api.tripjack.com", "hms-search.tripjack.com"));

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

        requireAllowedHost(request.getEnvironment(), request.getBaseUrl());
        String baseUrl = request.getBaseUrl().trim();
        boolean keyChanged = request.getApiKey() != null && !request.getApiKey().isBlank();
        boolean targetChanged = credential.getApiKeyEncrypted() != null
                && (credential.getEnvironment() != request.getEnvironment()
                        || !Objects.equals(credential.getBaseUrl(), baseUrl));
        if (targetChanged && !keyChanged) {
            // Re-pointing a stored key at another host would hand that host the key without anyone re-entering it.
            throw new IllegalArgumentException("Enter the API key again to change the environment or base URL");
        }
        if (keyChanged) {
            credential.setApiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey(), encryptionKey));
        } else if (credential.getApiKeyEncrypted() == null) {
            throw new IllegalArgumentException("API key is required");
        }
        if (request.getUserId() != null && !request.getUserId().isBlank()) {
            credential.setUserIdEncrypted(PasswordEncryptionUtil.encrypt(request.getUserId(), encryptionKey));
        }
        credential.setEnvironment(request.getEnvironment());
        credential.setBaseUrl(baseUrl);
        boolean activate = request.getIsActive() != null ? request.getIsActive() : Boolean.TRUE.equals(credential.getIsActive());
        if (activate) {
            // Others flushed before this row turns active: the partial unique index on (provider) WHERE is_active
            // would reject two active rows if both changes reached the same flush.
            List<PlatformSupplierCredential> others = rows.stream()
                    .filter(r -> r != credential && Boolean.TRUE.equals(r.getIsActive()))
                    .toList();
            others.forEach(r -> r.setIsActive(false));
            if (!others.isEmpty()) {
                repository.saveAllAndFlush(others);
            }
        }
        credential.setIsActive(activate);
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

    private static void requireAllowedHost(SupplierEnvironment environment, String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        boolean allowed = uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                && ALLOWED_HOSTS.get(environment).contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        if (!allowed) {
            throw new IllegalArgumentException("Base URL must be a Tripjack " + environment + " host over https");
        }
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
        requireAllowedHost(c.getEnvironment(), c.getBaseUrl());
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
