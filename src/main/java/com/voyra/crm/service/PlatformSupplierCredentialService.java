package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSupplierCreateRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.dto.PlatformSupplierUpdateRequest;
import com.voyra.crm.dto.SupplierProductSettings;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.entity.PlatformSupplierProduct;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.PlatformSupplierProductRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.PasswordEncryptionUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Voyra's platform-level supplier accounts: any number per integration, each with its search and booking switches per
 * product. Write-only: the key goes in encrypted (the same AES-256-GCM key as password retrieval) and comes out masked; the
 * only plaintext paths are {@link #resolveActive} (the providers) and {@link #resolve} (the test call), which no controller exposes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformSupplierCredentialService {

    static final String ENTITY_TYPE = "SUPPLIER_CREDENTIAL";

    static final String MOCK_URL = "mock://local";

    /** The only hosts the key may be sent to - a free-text URL would let it be pointed anywhere. */
    private static final Map<String, SupplierEnvironment> TRIPJACK_HOSTS = Map.of(
            "apitest.tripjack.com", SupplierEnvironment.UAT, "api.tripjack.com", SupplierEnvironment.PROD);

    /** A base URL as stored, and the environment its host decides. */
    record NormalizedUrl(String baseUrl, SupplierEnvironment environment) { }

    private final PlatformSupplierCredentialRepository repository;
    private final PlatformSupplierProductRepository productRepository;
    private final PlatformAuditService platformAuditService;
    private final Environment springEnv;

    @Value("${app.security.password-encryption-key}")
    private String encryptionKey;

    @Transactional(readOnly = true)
    public List<PlatformSupplierCredentialResponse> list() {
        List<PlatformSupplierCredential> rows = repository.findAllByOrderByNameAsc();
        Map<String, List<PlatformSupplierProduct>> byRow = productRepository
                .findByCredentialIdIn(rows.stream().map(PlatformSupplierCredential::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlatformSupplierProduct::getCredentialId));
        return rows.stream().map(c -> toResponse(c, byRow.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public PlatformSupplierCredentialResponse get(String id) {
        PlatformSupplierCredential c = require(id);
        return toResponse(c, productRepository.findByCredentialIdIn(List.of(id)));
    }

    @Transactional
    public PlatformSupplierCredentialResponse create(PlatformSupplierCreateRequest request) {
        if (request.getProvider() == SupplierProvider.MOCK && springEnv.matchesProfiles("cloudrun")) {
            throw new BookingRequestException("PROVIDER_UNAVAILABLE", "The mock supplier cannot be added on this server");
        }
        Map<PricingProduct, SupplierProductSettings> wanted = uniqueProducts(request.getProducts());
        NormalizedUrl url = normalizeBaseUrl(request.getProvider(), request.getBaseUrl());
        String name = request.getName().trim();
        if (repository.existsByNameIgnoreCase(name)) {
            throw nameTaken(name);
        }
        PlatformSupplierCredential credential = PlatformSupplierCredential.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .name(name)
                .provider(request.getProvider())
                .environment(url.environment())
                .baseUrl(url.baseUrl())
                .apiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey().trim(), encryptionKey))
                .updatedAt(Instant.now())
                .updatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        repository.saveAndFlush(credential);
        List<PlatformSupplierProduct> rows = syncProducts(credential.getId(), wanted);

        audit(credential, List.of("name", "baseUrl", "apiKey", "products"), wanted);
        log.info("Platform supplier created: provider={}, environment={}, by={}",
                credential.getProvider(), credential.getEnvironment(), SecurityContextUtil.getAuditInfo());
        return toResponse(credential, rows);
    }

    /**
     * Name, base URL, key and the product switches. The integration and environment never change. Only a name, URL or key
     * edit bumps the row's {@code updatedAt} (it expires open reviews); a switch change bumps its product row alone.
     */
    @Transactional
    public PlatformSupplierCredentialResponse update(String id, PlatformSupplierUpdateRequest request) {
        PlatformSupplierCredential credential = require(id);
        if (request.getProvider() != null && request.getProvider() != credential.getProvider()) {
            throw new IllegalArgumentException("The integration of a supplier cannot change");
        }
        Map<PricingProduct, SupplierProductSettings> wanted = uniqueProducts(request.getProducts());
        NormalizedUrl url = normalizeBaseUrl(credential.getProvider(), request.getBaseUrl());
        if (url.environment() != credential.getEnvironment()) {
            throw new IllegalArgumentException("Add a new supplier for the production account");
        }
        String name = request.getName().trim();
        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameTaken(name);
        }
        boolean keyChanged = request.getApiKey() != null && !request.getApiKey().isBlank();
        List<String> changed = new ArrayList<>();
        if (!name.equals(credential.getName())) changed.add("name");
        if (!url.baseUrl().equals(credential.getBaseUrl())) changed.add("baseUrl");
        if (keyChanged) changed.add("apiKey");
        if (!changed.isEmpty()) {
            credential.setName(name);
            credential.setBaseUrl(url.baseUrl());
            if (keyChanged) {
                credential.setApiKeyEncrypted(PasswordEncryptionUtil.encrypt(request.getApiKey().trim(), encryptionKey));
            }
            credential.setUpdatedAt(Instant.now());
            credential.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
            repository.save(credential);
        }
        // plain values: saveAll merges onto the managed rows, so comparing the entities afterwards would compare new with new
        Map<PricingProduct, List<Boolean>> before = switches(productRepository.findByCredentialIdIn(List.of(id)));
        List<PlatformSupplierProduct> rows = syncProducts(id, wanted);
        if (!before.equals(switches(rows))) changed.add("products");

        audit(credential, changed, wanted);
        log.info("Platform supplier saved: provider={}, changed={}, by={}",
                credential.getProvider(), changed, SecurityContextUtil.getAuditInfo());
        return toResponse(credential, rows);
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

    /** Every supplier with search switched on for the product, by name. */
    @Transactional(readOnly = true)
    public List<ResolvedCredential> searchEnabled(PricingProduct product) {
        return repository.findAllById(productRepository.findByProductAndSearchEnabledTrue(product).stream()
                        .map(PlatformSupplierProduct::getCredentialId).toList()).stream()
                .sorted(Comparator.comparing(PlatformSupplierCredential::getName))
                .map(this::decrypt).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedCredential> find(String id) {
        return repository.findById(id).map(this::decrypt);
    }

    /** Ids of the suppliers open for booking the product. */
    @Transactional(readOnly = true)
    public Set<String> bookingEnabledIds(PricingProduct product) {
        return productRepository.findByProductAndBookingEnabledTrue(product).stream()
                .map(PlatformSupplierProduct::getCredentialId).collect(Collectors.toSet());
    }

    /** Accepts only the hosts the integration may talk to and returns the stored form of the URL with its environment. */
    static NormalizedUrl normalizeBaseUrl(SupplierProvider provider, String baseUrl) {
        String trimmed = baseUrl.trim();
        if (provider == SupplierProvider.MOCK) {
            if (!MOCK_URL.equalsIgnoreCase(trimmed)) {
                throw new IllegalArgumentException("The mock supplier's base URL is " + MOCK_URL);
            }
            return new NormalizedUrl(MOCK_URL, SupplierEnvironment.MOCK);
        }
        String host = null;
        SupplierEnvironment environment = null;
        try {
            URI uri = URI.create(trimmed);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                host = uri.getHost().toLowerCase(Locale.ROOT);
                host = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
                environment = TRIPJACK_HOSTS.get(host);
            }
        } catch (IllegalArgumentException e) {
            environment = null;
        }
        if (environment == null) {
            throw new IllegalArgumentException("Base URL must be a Tripjack UAT or production host over https");
        }
        return new NormalizedUrl("https://" + host, environment);
    }

    private ResolvedCredential decrypt(PlatformSupplierCredential c) {
        // a row saved before the host guard existed must not keep sending the key to an off-list host
        if (normalizeBaseUrl(c.getProvider(), c.getBaseUrl()).environment() != c.getEnvironment()) {
            throw new IllegalArgumentException("Base URL does not match the supplier's environment");
        }
        return new ResolvedCredential(c.getId(), c.getName(), c.getProvider(), c.getEnvironment(), c.getBaseUrl(), plainKey(c),
                c.getUserIdEncrypted() != null ? PasswordEncryptionUtil.decrypt(c.getUserIdEncrypted(), encryptionKey) : null,
                c.getUpdatedAt());
    }

    /** A blank stored key (the migrated mock row) means no key. */
    private String plainKey(PlatformSupplierCredential c) {
        return c.getApiKeyEncrypted().isEmpty() ? "" : PasswordEncryptionUtil.decrypt(c.getApiKeyEncrypted(), encryptionKey);
    }

    private PlatformSupplierCredential require(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Supplier not found"));
    }

    private static BookingConflictException nameTaken(String name) {
        return new BookingConflictException("SUPPLIER_EXISTS", "A supplier named " + name + " already exists", Map.of());
    }

    private static Map<PricingProduct, SupplierProductSettings> uniqueProducts(List<SupplierProductSettings> products) {
        Map<PricingProduct, SupplierProductSettings> out = new EnumMap<>(PricingProduct.class);
        for (SupplierProductSettings s : products) {
            if (out.put(s.getProduct(), s) != null) {
                throw new IllegalArgumentException(s.getProduct() + " is listed twice");
            }
        }
        return out;
    }

    /** Makes the stored product rows exactly the wanted set; a row whose switches did not change is left as it is. */
    private List<PlatformSupplierProduct> syncProducts(String credentialId, Map<PricingProduct, SupplierProductSettings> wanted) {
        Map<PricingProduct, PlatformSupplierProduct> stored = productRepository.findByCredentialIdIn(List.of(credentialId)).stream()
                .collect(Collectors.toMap(PlatformSupplierProduct::getProduct, Function.identity()));
        productRepository.deleteAll(stored.values().stream().filter(p -> !wanted.containsKey(p.getProduct())).toList());
        String by = SecurityContextUtil.getCurrentUserOrThrow().userId();
        Instant now = Instant.now();
        List<PlatformSupplierProduct> out = new ArrayList<>();
        List<PlatformSupplierProduct> toSave = new ArrayList<>();
        wanted.forEach((product, s) -> {
            PlatformSupplierProduct row = stored.get(product);
            if (row != null && row.isSearchEnabled() == s.isSearchEnabled() && row.isBookingEnabled() == s.isBookingEnabled()) {
                out.add(row);
                return;
            }
            PlatformSupplierProduct next = PlatformSupplierProduct.builder().credentialId(credentialId).product(product)
                    .searchEnabled(s.isSearchEnabled()).bookingEnabled(s.isBookingEnabled()).updatedAt(now).updatedBy(by).build();
            out.add(next);
            toSave.add(next);
        });
        productRepository.saveAll(toSave);
        return out;
    }

    private static Map<PricingProduct, List<Boolean>> switches(List<PlatformSupplierProduct> rows) {
        return rows.stream().collect(Collectors.toMap(PlatformSupplierProduct::getProduct, p -> List.of(p.isSearchEnabled(), p.isBookingEnabled())));
    }

    /** Names the changed fields, never the key. */
    private void audit(PlatformSupplierCredential c, List<String> changed, Map<PricingProduct, SupplierProductSettings> products) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("credentialId", c.getId());
        detail.put("name", c.getName());
        detail.put("provider", c.getProvider().name());
        detail.put("environment", c.getEnvironment().name());
        detail.put("changed", changed);
        Map<String, Object> switches = new LinkedHashMap<>();
        products.forEach((p, s) -> switches.put(p.name(), Map.of("search", s.isSearchEnabled(), "booking", s.isBookingEnabled())));
        detail.put("products", switches);
        platformAuditService.record(PlatformAuditAction.CREDENTIAL_SET, ENTITY_TYPE, c.getId(), detail);
    }

    private PlatformSupplierCredentialResponse toResponse(PlatformSupplierCredential c, List<PlatformSupplierProduct> products) {
        String key = plainKey(c);
        return PlatformSupplierCredentialResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .provider(c.getProvider())
                .environment(c.getEnvironment())
                .baseUrl(c.getBaseUrl())
                .maskedApiKey(key.length() <= 4 ? "••••••" : "••••••" + key.substring(key.length() - 4))
                .products(products.stream()
                        .sorted(Comparator.comparing(PlatformSupplierProduct::getProduct))
                        .map(p -> new SupplierProductSettings(p.getProduct(), p.isSearchEnabled(), p.isBookingEnabled()))
                        .toList())
                .updatedAt(c.getUpdatedAt())
                .updatedBy(c.getUpdatedBy())
                .build();
    }
}
