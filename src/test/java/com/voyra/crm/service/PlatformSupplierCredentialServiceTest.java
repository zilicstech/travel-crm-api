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
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.BookingRequestException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.PlatformSupplierProductRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.util.PasswordEncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The platform's suppliers: any number per integration, key stored encrypted and shown masked, search and booking switches per product. */
@ExtendWith(MockitoExtension.class)
class PlatformSupplierCredentialServiceTest {

    /** Same value as application-test.properties - a real 32-byte AES-256 key, test-only. */
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformSupplierCredentialRepository repository;
    @Mock private PlatformSupplierProductRepository productRepository;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private Environment springEnv;

    @InjectMocks private PlatformSupplierCredentialService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "encryptionKey", KEY);
        lenient().when(productRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static SupplierProductSettings sw(PricingProduct product, boolean search, boolean booking) {
        return new SupplierProductSettings(product, search, booking);
    }

    private static PlatformSupplierCreateRequest createRequest(SupplierProvider provider, String baseUrl, String apiKey,
                                                               SupplierProductSettings... products) {
        PlatformSupplierCreateRequest r = new PlatformSupplierCreateRequest();
        r.setName(" Tripjack ");
        r.setProvider(provider);
        r.setBaseUrl(baseUrl);
        r.setApiKey(apiKey);
        r.setProducts(List.of(products));
        return r;
    }

    private static PlatformSupplierCreateRequest createRequest(String apiKey, SupplierProductSettings... products) {
        return createRequest(SupplierProvider.TRIPJACK, "https://api.tripjack.com", apiKey, products);
    }

    private static PlatformSupplierUpdateRequest updateRequest(String name, String baseUrl, String apiKey, SupplierProductSettings... products) {
        PlatformSupplierUpdateRequest r = new PlatformSupplierUpdateRequest();
        r.setName(name);
        r.setBaseUrl(baseUrl);
        r.setApiKey(apiKey);
        r.setProducts(List.of(products));
        return r;
    }

    private PlatformSupplierCredential stored(String plainKey, SupplierEnvironment env, String baseUrl) {
        return PlatformSupplierCredential.builder()
                .id("C1").name("Tripjack").provider(SupplierProvider.TRIPJACK).environment(env).baseUrl(baseUrl)
                .apiKeyEncrypted(PasswordEncryptionUtil.encrypt(plainKey, KEY))
                .updatedAt(OLD).isActive(true).build();
    }

    private PlatformSupplierCredential stored(String plainKey) {
        return stored(plainKey, SupplierEnvironment.PROD, "https://api.tripjack.com");
    }

    private static PlatformSupplierProduct row(PricingProduct product, boolean search, boolean booking) {
        return PlatformSupplierProduct.builder().credentialId("C1").product(product).searchEnabled(search).bookingEnabled(booking)
                .updatedAt(OLD).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail(PlatformAuditAction action) {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(action), eq("SUPPLIER_CREDENTIAL"), eq("C1"), detail.capture());
        return detail.getValue();
    }

    @Test
    void createStoresTheNormalizedUrlAndTheEnvironmentItDecides() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> {
            PlatformSupplierCredential c = i.getArgument(0);
            c.setId("C1");
            return c;
        });

        PlatformSupplierCredentialResponse prod = service.create(createRequest(SupplierProvider.TRIPJACK,
                "https://API.Tripjack.com./", "tj_live_key_9f3a", sw(PricingProduct.HOTEL, true, false), sw(PricingProduct.FLIGHT, true, true)));
        PlatformSupplierCredentialResponse uat = service.create(createRequest(SupplierProvider.TRIPJACK,
                "https://apitest.tripjack.com", "tj_uat_key_1111", sw(PricingProduct.FLIGHT, false, false)));

        ArgumentCaptor<PlatformSupplierCredential> saved = ArgumentCaptor.forClass(PlatformSupplierCredential.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(0).getName()).isEqualTo("Tripjack");
        assertThat(saved.getAllValues().get(0).getApiKeyEncrypted()).isNotEqualTo("tj_live_key_9f3a");
        assertThat(PasswordEncryptionUtil.decrypt(saved.getAllValues().get(0).getApiKeyEncrypted(), KEY)).isEqualTo("tj_live_key_9f3a");
        assertThat(prod.getBaseUrl()).isEqualTo("https://api.tripjack.com");
        assertThat(prod.getEnvironment()).isEqualTo(SupplierEnvironment.PROD);
        assertThat(prod.getMaskedApiKey()).isEqualTo("••••••9f3a");
        assertThat(prod.getUpdatedBy()).isEqualTo("SA1");
        assertThat(prod.getProducts()).containsExactly(sw(PricingProduct.FLIGHT, true, true), sw(PricingProduct.HOTEL, true, false));
        assertThat(uat.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.tripjack.com", "https://api.tripjack.com:8443", "https://u@api.tripjack.com",
            "https://api.tripjack.com/x", "https://api.tripjack.com?a=1", "https://api.tripjack.com/#f", "https://evil.example.com",
            "https:///nohost", "https://api.trip jack.com"})
    void createRefusesAUrlOffTheAllowList(String url) {
        assertThatThrownBy(() -> service.create(createRequest(SupplierProvider.TRIPJACK, url, "k_12345", sw(PricingProduct.FLIGHT, true, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a Tripjack UAT or production host over https");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theMockTakesMockLocalOnly() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(createRequest(SupplierProvider.MOCK, " MOCK://local ", "", sw(PricingProduct.FLIGHT, true, true))))
                .satisfies(r -> {
                    assertThat(r.getEnvironment()).isEqualTo(SupplierEnvironment.MOCK);
                    assertThat(r.getBaseUrl()).isEqualTo("mock://local");
                    assertThat(r.getMaskedApiKey()).isEqualTo("••••••");
                });
        assertThatThrownBy(() -> service.create(createRequest(SupplierProvider.MOCK, "https://api.tripjack.com", "k_12345",
                sw(PricingProduct.FLIGHT, true, true)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The mock supplier's base URL is mock://local");
    }

    @Test
    void creatingAMockRowOnCloudrunIs400() {
        when(springEnv.matchesProfiles("cloudrun")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest(SupplierProvider.MOCK, "mock://local", "k_12345",
                sw(PricingProduct.FLIGHT, true, true))))
                .isInstanceOf(BookingRequestException.class).hasMessage("The mock supplier cannot be added on this server")
                .satisfies(e -> assertThat(((BookingRequestException) e).getCode()).isEqualTo("PROVIDER_UNAVAILABLE"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aNameTakenIgnoringCaseIs409() {
        when(repository.existsByNameIgnoreCase("tripjack")).thenReturn(true);
        PlatformSupplierCreateRequest r = createRequest("k_12345", sw(PricingProduct.FLIGHT, true, true));
        r.setName("tripjack");

        assertThatThrownBy(() -> service.create(r)).isInstanceOf(BookingConflictException.class)
                .hasMessage("A supplier named tripjack already exists")
                .satisfies(e -> assertThat(((BookingConflictException) e).getCode()).isEqualTo("SUPPLIER_EXISTS"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aProductListedTwiceIs400() {
        assertThatThrownBy(() -> service.create(createRequest("k_12345", sw(PricingProduct.FLIGHT, true, true), sw(PricingProduct.FLIGHT, false, false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("FLIGHT is listed twice");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void auditNamesTheChangedFieldsAndNeverTheKey() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> {
            PlatformSupplierCredential c = i.getArgument(0);
            c.setId("C1");
            return c;
        });

        service.create(createRequest("tj_live_key_9f3a", sw(PricingProduct.FLIGHT, true, false)));

        Map<String, Object> detail = auditDetail(PlatformAuditAction.CREDENTIAL_SET);
        assertThat(detail).containsEntry("name", "Tripjack").containsEntry("environment", "PROD")
                .containsEntry("changed", List.of("name", "baseUrl", "apiKey", "products"))
                .containsEntry("products", Map.of("FLIGHT", Map.of("search", true, "booking", false)));
        assertThat(detail.toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("9f3a");
    }

    @Test
    void updateRefusesAnotherProvider() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("old_key_1234")));
        PlatformSupplierUpdateRequest r = updateRequest("Tripjack", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, true));
        r.setProvider(SupplierProvider.MOCK);

        assertThatThrownBy(() -> service.update("C1", r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The integration of a supplier cannot change");
    }

    @Test
    void updateRefusesAUrlOfTheOtherEnvironment() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("old_key_1234", SupplierEnvironment.UAT, "https://apitest.tripjack.com")));

        assertThatThrownBy(() -> service.update("C1", updateRequest("Tripjack", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Add a new supplier for the production account");
        verify(repository, never()).save(any());
    }

    @Test
    void updateRefusesANameTakenByAnotherSupplier() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("old_key_1234")));
        when(repository.existsByNameIgnoreCaseAndIdNot("Other", "C1")).thenReturn(true);

        assertThatThrownBy(() -> service.update("C1", updateRequest("Other", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, true))))
                .isInstanceOf(BookingConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateReplacesTheProductSetAndKeepsABlankKey() {
        PlatformSupplierCredential existing = stored("old_key_1234");
        String cipher = existing.getApiKeyEncrypted();
        PlatformSupplierProduct flight = row(PricingProduct.FLIGHT, true, true);
        PlatformSupplierProduct hotel = row(PricingProduct.HOTEL, true, false);
        when(repository.findById("C1")).thenReturn(Optional.of(existing));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(flight, hotel));

        PlatformSupplierCredentialResponse response = service.update("C1",
                updateRequest("Tripjack India", "https://API.tripjack.com/", "  ", sw(PricingProduct.FLIGHT, true, true)));

        assertThat(existing.getApiKeyEncrypted()).isEqualTo(cipher);
        assertThat(existing.getName()).isEqualTo("Tripjack India");
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••1234");
        assertThat(response.getProducts()).containsExactly(sw(PricingProduct.FLIGHT, true, true));
        verify(productRepository).deleteAll(List.of(hotel));
        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET).get("changed")).isEqualTo(List.of("name", "products"));
    }

    @Test
    void aNewKeyIsEncryptedAndTheEnvironmentStays() {
        PlatformSupplierCredential existing = stored("old_key_1234", SupplierEnvironment.UAT, "https://apitest.tripjack.com");
        when(repository.findById("C1")).thenReturn(Optional.of(existing));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(row(PricingProduct.FLIGHT, true, true)));

        PlatformSupplierCredentialResponse response = service.update("C1",
                updateRequest("Tripjack", "https://apitest.tripjack.com", "tj_uat_key_7777", sw(PricingProduct.FLIGHT, true, true)));

        assertThat(existing.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••7777");
        assertThat(existing.getUpdatedAt()).isAfter(OLD);
        assertThat(existing.getUpdatedBy()).isEqualTo("SA1");
        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET).get("changed")).isEqualTo(List.of("apiKey"));
    }

    @Test
    void onlyANameUrlOrKeyEditBumpsUpdatedAt() {
        PlatformSupplierCredential existing = stored("old_key_1234");
        PlatformSupplierProduct flight = row(PricingProduct.FLIGHT, true, true);
        when(repository.findById("C1")).thenReturn(Optional.of(existing));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(flight));

        PlatformSupplierCredentialResponse response = service.update("C1",
                updateRequest("Tripjack", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, false), sw(PricingProduct.HOTEL, true, true)));

        assertThat(existing.getUpdatedAt()).isEqualTo(OLD);
        verify(repository, never()).save(any());
        assertThat(response.getUpdatedAt()).isEqualTo(OLD);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlatformSupplierProduct>> saved = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2).allSatisfy(p -> {
            assertThat(p.getUpdatedAt()).isAfter(OLD);
            assertThat(p.getUpdatedBy()).isEqualTo("SA1");
        });
        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET).get("changed")).isEqualTo(List.of("products"));
    }

    /** The real repository merges saveAll onto the managed rows it returned from the find; the audit must still see the change. */
    @Test
    void aSwitchOnlyChangeIsAuditedEvenWhenSaveMutatesTheManagedRows() {
        PlatformSupplierProduct managed = row(PricingProduct.FLIGHT, true, true);
        when(repository.findById("C1")).thenReturn(Optional.of(stored("old_key_1234")));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(managed));
        when(productRepository.saveAll(anyList())).thenAnswer(i -> {
            List<PlatformSupplierProduct> saved = i.getArgument(0);
            saved.forEach(n -> managed.setBookingEnabled(n.isBookingEnabled()));
            return saved;
        });

        service.update("C1", updateRequest("Tripjack", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, false)));

        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET).get("changed")).isEqualTo(List.of("products"));
    }

    @Test
    void anUnchangedSaveChangesNothing() {
        PlatformSupplierCredential existing = stored("old_key_1234");
        when(repository.findById("C1")).thenReturn(Optional.of(existing));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(row(PricingProduct.FLIGHT, true, true)));

        service.update("C1", updateRequest("Tripjack", "https://api.tripjack.com", null, sw(PricingProduct.FLIGHT, true, true)));

        verify(repository, never()).save(any());
        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET).get("changed")).isEqualTo(List.of());
    }

    @Test
    void aBlankStoredKeyMasksAsDots() {
        PlatformSupplierCredential mock = stored("x", SupplierEnvironment.MOCK, "mock://local");
        mock.setProvider(SupplierProvider.MOCK);
        mock.setApiKeyEncrypted("");
        when(repository.findById("C1")).thenReturn(Optional.of(mock));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of());

        assertThat(service.get("C1").getMaskedApiKey()).isEqualTo("••••••");
        assertThat(service.resolve("C1").apiKey()).isEmpty();
    }

    @Test
    void resolvedCredentialToStringMasksTheKey() {
        ResolvedCredential c = new ResolvedCredential("s1", "Tripjack", SupplierProvider.TRIPJACK, SupplierEnvironment.UAT,
                "https://apitest.tripjack.com", "tj_live_key_9f3a", "voyra-user-0042", null);

        assertThat(c.toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("voyra-user-0042")
                .contains("••••••9f3a").contains("••••••0042");
    }

    @Test
    void resolveActiveReturnsDecryptedKey() {
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(stored("tj_live_key_9f3a")));

        ResolvedCredential credential = service.resolveActive(SupplierProvider.TRIPJACK);

        assertThat(credential.apiKey()).isEqualTo("tj_live_key_9f3a");
        assertThat(credential.environment()).isEqualTo(SupplierEnvironment.PROD);
        assertThat(credential.baseUrl()).isEqualTo("https://api.tripjack.com");
        assertThat(credential.userId()).isNull();
    }

    @Test
    void resolveByIdWorksForAnInactiveRow() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_off_key_1111")));

        assertThat(service.resolve("C1").apiKey()).isEqualTo("tj_off_key_1111");
    }

    /** A row saved before the host guard existed must not keep sending the key to an off-list host. */
    @Test
    void resolveRefusesAStoredHostOffTheAllowlist() {
        PlatformSupplierCredential old = stored("tj_live_key_9f3a", SupplierEnvironment.PROD, "https://attacker.example.com");
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(old));
        when(repository.findById("C1")).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.resolveActive(SupplierProvider.TRIPJACK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve("C1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveActiveWithoutARowThrows() {
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveActive(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tripjack is not configured on the platform");
    }

    @Test
    void listMasksEveryKey() {
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(stored("tj_live_key_9f3a")));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of());

        assertThat(service.list()).singleElement().satisfies(r -> {
            assertThat(r.getMaskedApiKey()).isEqualTo("••••••9f3a");
            assertThat(r.toString()).doesNotContain("tj_live_key_9f3a");
        });
    }

    @Test
    void thereIsNoRevealMethod() {
        assertThat(Arrays.stream(PlatformSupplierCredentialService.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .doesNotContain("reveal");
    }

    // ---- remaining guards

    @Test
    void getReturnsOneSupplierWithItsSortedSwitchesAndAShortKeyFullyMasked() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("abcd")));
        when(productRepository.findByCredentialIdIn(List.of("C1"))).thenReturn(List.of(
                row(PricingProduct.HOTEL, true, false), row(PricingProduct.FLIGHT, false, false)));

        var r = service.get("C1");

        assertThat(r.getMaskedApiKey()).isEqualTo("••••••");
        assertThat(r.getProducts()).extracting(SupplierProductSettings::getProduct).containsExactly(PricingProduct.FLIGHT, PricingProduct.HOTEL);
    }

    @Test
    void anUnknownSupplierIsNotFound() {
        when(repository.findById("C9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("C9")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessage("Supplier not found");
    }

    @Test
    void searchEnabledReturnsTheSwitchedOnRowsByNameWithTheirIdentity() {
        PlatformSupplierCredential b = stored("kb").toBuilder().id("C2").name("Beta").build();
        PlatformSupplierCredential a = stored("ka").toBuilder().id("C3").name("Alpha").build();
        when(productRepository.findByProductAndSearchEnabledTrue(PricingProduct.FLIGHT))
                .thenReturn(List.of(row(PricingProduct.FLIGHT, true, false).toBuilder().credentialId("C2").build(),
                        row(PricingProduct.FLIGHT, true, false).toBuilder().credentialId("C3").build()));
        when(repository.findAllById(List.of("C2", "C3"))).thenReturn(List.of(b, a));

        List<ResolvedCredential> found = service.searchEnabled(PricingProduct.FLIGHT);

        assertThat(found).extracting(ResolvedCredential::name).containsExactly("Alpha", "Beta");
        assertThat(found).extracting(ResolvedCredential::id).containsExactly("C3", "C2");
        assertThat(found.get(0).apiKey()).isEqualTo("ka");
        assertThat(found.get(0).updatedAt()).isEqualTo(OLD);
    }

    @Test
    void searchEnabledSkipsAnUnreadableRowAndKeepsTheOthers() {
        PlatformSupplierCredential bad = stored("kb").toBuilder().id("C2").name("Bad").baseUrl("https://evil.example.com").build();
        PlatformSupplierCredential good = stored("ka").toBuilder().id("C3").name("Good").build();
        when(productRepository.findByProductAndSearchEnabledTrue(PricingProduct.FLIGHT))
                .thenReturn(List.of(row(PricingProduct.FLIGHT, true, false).toBuilder().credentialId("C2").build(),
                        row(PricingProduct.FLIGHT, true, false).toBuilder().credentialId("C3").build()));
        when(repository.findAllById(List.of("C2", "C3"))).thenReturn(List.of(bad, good));

        try (com.voyra.crm.support.LogCapture log = new com.voyra.crm.support.LogCapture(PlatformSupplierCredentialService.class)) {
            assertThat(service.searchEnabled(PricingProduct.FLIGHT)).extracting(ResolvedCredential::name).containsExactly("Good");
            assertThat(log.lines()).containsExactly("WARN Supplier Bad cannot be used for search: IllegalArgumentException");
        }
    }

    @Test
    void findResolvesOneRowOrNothing() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("k")));
        when(repository.findById("nope")).thenReturn(Optional.empty());

        assertThat(service.find("C1")).get().extracting(ResolvedCredential::name).isEqualTo("Tripjack");
        assertThat(service.find("nope")).isEmpty();
    }

    @Test
    void bookingEnabledIdsAreTheRowsOpenForBooking() {
        when(productRepository.findByProductAndBookingEnabledTrue(PricingProduct.HOTEL))
                .thenReturn(List.of(row(PricingProduct.HOTEL, false, true)));

        assertThat(service.bookingEnabledIds(PricingProduct.HOTEL)).containsExactly("C1");
    }

    @Test
    void aStoredUserIdIsDecryptedToo() {
        PlatformSupplierCredential c = stored("tj_live_key_9f3a");
        c.setUserIdEncrypted(PasswordEncryptionUtil.encrypt("agency-user-1", KEY));
        when(repository.findById("C1")).thenReturn(Optional.of(c));

        assertThat(service.resolve("C1").userId()).isEqualTo("agency-user-1");
    }

    @Test
    void theKeyIsSentOnlyToAPlainHttpsTripjackHostOfTheRowsEnvironment() {
        for (String url : List.of("http://api.tripjack.com", "https://user@api.tripjack.com", "https://api.tripjack.com:8443",
                "https://api.tripjack.com/?x=1", "https://api.tripjack.com/#f", "https://api.tripjack.com/v1", "https://api.trip jack.com")) {
            when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_live_key_9f3a", SupplierEnvironment.PROD, url)));
            assertThatThrownBy(() -> service.resolve("C1")).as(url).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Base URL must be a Tripjack UAT or production host over https");
        }
        when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_live_key_9f3a", SupplierEnvironment.PROD, "https://apitest.tripjack.com")));
        assertThatThrownBy(() -> service.resolve("C1")).hasMessage("Base URL does not match the supplier's environment");
        when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_live_key_9f3a", SupplierEnvironment.PROD, "https://API.tripjack.com/")));
        assertThat(service.resolve("C1").apiKey()).isEqualTo("tj_live_key_9f3a");
        when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_live_key_9f3a", SupplierEnvironment.UAT, "https://apitest.tripjack.com")));
        assertThat(service.resolve("C1").environment()).isEqualTo(SupplierEnvironment.UAT);
    }
}
