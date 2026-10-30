package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSupplierCreateRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.PlatformSupplierUpdateRequest;
import com.voyra.crm.dto.SupplierProductTerms;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.entity.PlatformSupplierProduct;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.PlatformSupplierProductRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PasswordEncryptionUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Voyra's platform-level supplier accounts, one row per integration, each with its commission terms per product.
 * Write-only: the key goes in encrypted (the same AES-256-GCM key as password retrieval) and comes out masked; the only
 * plaintext paths are {@link #resolveActive} (the providers) and {@link #resolve} (the test call), which no controller exposes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformSupplierCredentialService {

    static final String ENTITY_TYPE = "SUPPLIER_CREDENTIAL";

    /** Used when a supplier has no terms for a product yet (the old platform default), so pricing never breaks. */
    static final BigDecimal DEFAULT_COMMISSION_PCT = BigDecimal.valueOf(5);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The only hosts the platform key may be sent to - a free-text URL would let the key be pointed anywhere. */
    static final Map<SupplierEnvironment, Set<String>> ALLOWED_HOSTS = Map.of(
            SupplierEnvironment.UAT, Set.of("apitest.tripjack.com", "apitest-hms.tripjack.com"),
            SupplierEnvironment.PROD, Set.of("api.tripjack.com", "hms-search.tripjack.com"));

    /** Suppliers are production only (owner 2026-10-30): a new row always points here. */
    static final Map<SupplierProvider, String> PROD_BASE_URL = Map.of(SupplierProvider.TRIPJACK, "https://api.tripjack.com");

    private final PlatformSupplierCredentialRepository repository;
    private final PlatformSupplierProductRepository productRepository;
    private final PlatformAuditService platformAuditService;
    private final PlatformBookingRepository bookingRepository;

    @Value("${app.security.password-encryption-key}")
    private String encryptionKey;

    @Transactional(readOnly = true)
    public List<PlatformSupplierCredentialResponse> list() {
        List<PlatformSupplierCredential> rows = repository.findAllByOrderByNameAsc();
        Map<String, List<PlatformSupplierProduct>> terms = productRepository
                .findByCredentialIdIn(rows.stream().map(PlatformSupplierCredential::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlatformSupplierProduct::getCredentialId));
        return rows.stream().map(c -> toResponse(c, terms.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public PlatformSupplierCredentialResponse get(String id) {
        PlatformSupplierCredential c = require(id);
        return toResponse(c, productRepository.findByCredentialIdIn(List.of(id)));
    }

    @Transactional
    public PlatformSupplierCredentialResponse create(PlatformSupplierCreateRequest request) {
        Map<PricingProduct, BigDecimal> terms = validTerms(request.getProducts());
        if (repository.existsByProvider(request.getProvider())) {
            throw new BookingConflictException("SUPPLIER_EXISTS",
                    request.getProvider() + " is already set up - edit that supplier instead", Map.of());
        }
        String baseUrl = PROD_BASE_URL.get(request.getProvider());
        if (baseUrl == null) {
            throw new IllegalArgumentException(request.getProvider() + " has no production host configured");
        }
        requireNoBookingsInFlight();
        requireAllowedHost(SupplierEnvironment.PROD, baseUrl);
        PlatformSupplierCredential credential = PlatformSupplierCredential.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .name(request.getName().trim())
                .provider(request.getProvider())
                .environment(SupplierEnvironment.PROD)
                .baseUrl(baseUrl)
                .apiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey().trim(), encryptionKey))
                .isActive(!Boolean.FALSE.equals(request.getIsActive()))
                .updatedAt(Instant.now())
                .updatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        repository.saveAndFlush(credential);
        for (PricingProduct product : PricingProduct.values()) {
            terms.putIfAbsent(product, DEFAULT_COMMISSION_PCT);
        }
        List<PlatformSupplierProduct> rows = saveTerms(credential.getId(), terms);

        Map<String, Object> detail = credentialDetail(credential, true);
        detail.put("products", termsDetail(terms));
        platformAuditService.record(PlatformAuditAction.CREDENTIAL_SET, ENTITY_TYPE, credential.getId(), detail);
        log.info("Platform supplier created: provider={}, active={}, by={}",
                credential.getProvider(), credential.getIsActive(), SecurityContextUtil.getAuditInfo());
        return toResponse(credential, rows);
    }

    /** Name, key and active flag. The stored environment and base URL never change here, a new key included. */
    @Transactional
    public PlatformSupplierCredentialResponse update(String id, PlatformSupplierUpdateRequest request) {
        PlatformSupplierCredential credential = require(id);
        boolean keyChanged = request.getApiKey() != null && !request.getApiKey().isBlank();
        boolean activate = request.getIsActive() != null ? request.getIsActive() : Boolean.TRUE.equals(credential.getIsActive());
        if (keyChanged || activate != Boolean.TRUE.equals(credential.getIsActive())) {
            requireNoBookingsInFlight();
        }
        if (keyChanged) {
            credential.setApiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey().trim(), encryptionKey));
        }
        credential.setName(request.getName().trim());
        credential.setIsActive(activate);
        credential.setUpdatedAt(Instant.now());
        credential.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        repository.save(credential);

        platformAuditService.record(PlatformAuditAction.CREDENTIAL_SET, ENTITY_TYPE, credential.getId(),
                credentialDetail(credential, keyChanged));
        log.info("Platform supplier saved: provider={}, active={}, keyChanged={}, by={}",
                credential.getProvider(), credential.getIsActive(), keyChanged, SecurityContextUtil.getAuditInfo());
        return toResponse(credential, productRepository.findByCredentialIdIn(List.of(id)));
    }

    /** Terms only: no supplier call depends on them, so this is never refused for bookings in flight (review item 1). */
    @Transactional
    public PlatformSupplierCredentialResponse updateProducts(String id, List<SupplierProductTerms> products) {
        PlatformSupplierCredential credential = require(id);
        Map<PricingProduct, BigDecimal> terms = validTerms(products);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("Give the commission for at least one product");
        }
        saveTerms(id, terms);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("credentialId", id);
        detail.put("products", termsDetail(terms));
        platformAuditService.record(PlatformAuditAction.SUPPLIER_TERMS_SET, ENTITY_TYPE, id, detail);
        log.info("Platform supplier terms saved: provider={}, terms={}, by={}",
                credential.getProvider(), terms, SecurityContextUtil.getAuditInfo());
        return toResponse(credential, productRepository.findByCredentialIdIn(List.of(id)));
    }

    /**
     * Voyra's estimated commission % for a product, from the active supplier serving it. Tripjack is the only
     * integration today and serves both products; mock mode prices with the same row, as the old global setting did.
     */
    @Transactional(readOnly = true)
    public BigDecimal commissionEstimatePct(PricingProduct product) {
        return repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)
                .flatMap(c -> productRepository.findByCredentialIdAndProduct(c.getId(), product))
                .map(PlatformSupplierProduct::getCommissionEstimatePct)
                .orElse(DEFAULT_COMMISSION_PCT);
    }

    /** The only way a search or booking provider gets a key - resolved per call, so a key change applies to the next call. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolveActive(SupplierProvider provider) {
        PlatformSupplierCredential c = repository.findByProviderAndIsActiveTrue(provider)
                .orElseThrow(() -> new IllegalStateException("Tripjack is not configured on the platform"));
        return decrypt(c);
    }

    /** One row's key, active or not - only for the test call on that row. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolve(String id) {
        return decrypt(require(id));
    }

    @Transactional(readOnly = true)
    public boolean hasActive(SupplierProvider provider) {
        return repository.findByProviderAndIsActiveTrue(provider).isPresent();
    }

    private ResolvedCredential decrypt(PlatformSupplierCredential c) {
        requireAllowedHost(c.getEnvironment(), c.getBaseUrl());
        return new ResolvedCredential(c.getProvider(), c.getEnvironment(), c.getBaseUrl(),
                PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey),
                c.getUserIdEncrypted() != null ? PasswordEncryptionUtil.decrypt(c.getUserIdEncrypted(), encryptionKey) : null);
    }

    private PlatformSupplierCredential require(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Supplier not found"));
    }

    private void requireNoBookingsInFlight() {
        // in-flight bookings and unanswered cancels are settled with the stamped credential; switching it strands them
        long inFlight = bookingRepository.countByStatusIn(PlatformBookingStatus.SUPPLIER_PENDING);
        if (inFlight > 0) {
            throw new IllegalStateException(inFlight + " booking(s) are still held, booking, unknown or cancelling"
                    + " - wait for them to finish before changing the supplier key or turning it on or off");
        }
    }

    private static Map<PricingProduct, BigDecimal> validTerms(List<SupplierProductTerms> products) {
        Map<PricingProduct, BigDecimal> terms = new EnumMap<>(PricingProduct.class);
        for (SupplierProductTerms t : products == null ? List.<SupplierProductTerms>of() : products) {
            if (t == null || t.getProduct() == null) {
                throw new IllegalArgumentException("Product is required");
            }
            BigDecimal pct = t.getCommissionEstimatePct();
            if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("Commission % must be between 0 and 100");
            }
            if (terms.put(t.getProduct(), pct) != null) {
                throw new IllegalArgumentException(t.getProduct() + " is listed twice");
            }
        }
        return terms;
    }

    private List<PlatformSupplierProduct> saveTerms(String credentialId, Map<PricingProduct, BigDecimal> terms) {
        String by = SecurityContextUtil.getCurrentUserOrThrow().userId();
        Instant now = Instant.now();
        return productRepository.saveAll(terms.entrySet().stream()
                .map(e -> PlatformSupplierProduct.builder().credentialId(credentialId).product(e.getKey())
                        .commissionEstimatePct(e.getValue()).updatedAt(now).updatedBy(by).build())
                .toList());
    }

    private static Map<String, Object> credentialDetail(PlatformSupplierCredential c, boolean keyChanged) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("credentialId", c.getId());
        detail.put("name", c.getName());
        detail.put("provider", c.getProvider().name());
        detail.put("environment", c.getEnvironment().name());
        detail.put("isActive", c.getIsActive());
        detail.put("keyChanged", keyChanged);
        return detail;
    }

    private static Map<String, Object> termsDetail(Map<PricingProduct, BigDecimal> terms) {
        Map<String, Object> out = new LinkedHashMap<>();
        terms.forEach((p, pct) -> out.put(p.name(), pct));
        return out;
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
                && ALLOWED_HOSTS.getOrDefault(environment, Set.of()).contains(uri.getHost().toLowerCase(Locale.ROOT));
        if (!allowed) {
            throw new IllegalArgumentException("Base URL must be a Tripjack " + environment + " host over https");
        }
    }

    private PlatformSupplierCredentialResponse toResponse(PlatformSupplierCredential c, List<PlatformSupplierProduct> products) {
        String key = PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey);
        return PlatformSupplierCredentialResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .provider(c.getProvider())
                .environment(c.getEnvironment())
                .maskedApiKey(key.length() <= 4 ? "••••••" : "••••••" + key.substring(key.length() - 4))
                .isActive(c.getIsActive())
                .products(products.stream()
                        .sorted(Comparator.comparing(PlatformSupplierProduct::getProduct))
                        .map(p -> new SupplierProductTerms(p.getProduct(), p.getCommissionEstimatePct()))
                        .toList())
                .updatedAt(c.getUpdatedAt())
                .updatedBy(c.getUpdatedBy())
                .build();
    }
}
