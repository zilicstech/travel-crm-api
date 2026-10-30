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
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.repository.PlatformSupplierProductRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.util.PasswordEncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The platform's suppliers: one per integration, key stored encrypted and shown masked, terms per product, PROD only. */
@ExtendWith(MockitoExtension.class)
class PlatformSupplierCredentialServiceTest {

    /** Same value as application-test.properties - a real 32-byte AES-256 key, test-only. */
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformSupplierCredentialRepository repository;
    @Mock private PlatformSupplierProductRepository productRepository;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private PlatformBookingRepository bookingRepository;

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

    private static PlatformSupplierCreateRequest createRequest(String apiKey, SupplierProductTerms... terms) {
        PlatformSupplierCreateRequest r = new PlatformSupplierCreateRequest();
        r.setName("Tripjack");
        r.setProvider(SupplierProvider.TRIPJACK);
        r.setApiKey(apiKey);
        r.setIsActive(true);
        r.setProducts(List.of(terms));
        return r;
    }

    private static PlatformSupplierUpdateRequest updateRequest(String apiKey, Boolean active) {
        PlatformSupplierUpdateRequest r = new PlatformSupplierUpdateRequest();
        r.setName("Tripjack India");
        r.setApiKey(apiKey);
        r.setIsActive(active);
        return r;
    }

    private static SupplierProductTerms terms(PricingProduct product, String pct) {
        return new SupplierProductTerms(product, pct == null ? null : new BigDecimal(pct));
    }

    private PlatformSupplierCredential stored(String plainKey, boolean active, SupplierEnvironment env, String baseUrl) {
        return PlatformSupplierCredential.builder()
                .id("C1").name("Tripjack").provider(SupplierProvider.TRIPJACK).environment(env).baseUrl(baseUrl)
                .apiKeyEncrypted(PasswordEncryptionUtil.encrypt(plainKey, KEY))
                .isActive(active).build();
    }

    private PlatformSupplierCredential stored(String plainKey, boolean active) {
        return stored(plainKey, active, SupplierEnvironment.PROD, "https://api.tripjack.com");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail(PlatformAuditAction action) {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(action), eq("SUPPLIER_CREDENTIAL"), eq("C1"), detail.capture());
        return detail.getValue();
    }

    @Test
    void createIsProdOnTheProductionHostWithAnEncryptedMaskedKey() {
        when(repository.existsById(any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(i -> {
            PlatformSupplierCredential c = i.getArgument(0);
            c.setId("C1");
            return c;
        });

        PlatformSupplierCredentialResponse response = service.create(createRequest("tj_live_key_9f3a",
                terms(PricingProduct.FLIGHT, "4.5")));

        ArgumentCaptor<PlatformSupplierCredential> saved = ArgumentCaptor.forClass(PlatformSupplierCredential.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEnvironment()).isEqualTo(SupplierEnvironment.PROD);
        assertThat(saved.getValue().getBaseUrl()).isEqualTo("https://api.tripjack.com");
        assertThat(saved.getValue().getApiKeyEncrypted()).isNotEqualTo("tj_live_key_9f3a");
        assertThat(PasswordEncryptionUtil.decrypt(saved.getValue().getApiKeyEncrypted(), KEY)).isEqualTo("tj_live_key_9f3a");
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••9f3a");
        assertThat(response.getUpdatedBy()).isEqualTo("SA1");
        // a product left out gets the 5% default, so pricing has terms for both
        assertThat(response.getProducts()).extracting(SupplierProductTerms::getProduct, SupplierProductTerms::getCommissionEstimatePct)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PricingProduct.FLIGHT, new BigDecimal("4.5")),
                        org.assertj.core.groups.Tuple.tuple(PricingProduct.HOTEL, BigDecimal.valueOf(5)));
        Map<String, Object> detail = auditDetail(PlatformAuditAction.CREDENTIAL_SET);
        assertThat(detail).containsEntry("name", "Tripjack").containsEntry("keyChanged", true).containsKey("products");
        assertThat(detail.toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("9f3a");
    }

    @Test
    void aSecondSupplierForTheSameIntegrationIsRefused() {
        when(repository.existsByProvider(SupplierProvider.TRIPJACK)).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("tj_live_key_9f3a")))
                .isInstanceOf(BookingConflictException.class)
                .satisfies(e -> assertThat(((BookingConflictException) e).getCode()).isEqualTo("SUPPLIER_EXISTS"));
        verify(repository, never()).saveAndFlush(any());
    }

    /** Owner 2026-10-30: the key is saved and tested before going live; the live flag gates bookings, not saves. */
    @Test
    void createDoesNotNeedTheLiveSwitch() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(createRequest("tj_live_key_9f3a")).getEnvironment()).isEqualTo(SupplierEnvironment.PROD);
    }

    @Test
    void termsOutOfRangeOrRepeatedAreRejected() {
        for (PlatformSupplierCreateRequest r : List.of(
                createRequest("k_12345", terms(PricingProduct.FLIGHT, "-1")),
                createRequest("k_12345", terms(PricingProduct.FLIGHT, "100.01")),
                createRequest("k_12345", terms(PricingProduct.FLIGHT, null)),
                createRequest("k_12345", terms(null, "5")),
                createRequest("k_12345", terms(PricingProduct.HOTEL, "5"), terms(PricingProduct.HOTEL, "6")))) {
            assertThatThrownBy(() -> service.create(r)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateWithABlankKeyKeepsTheStoredKeyAndEnvironment() {
        PlatformSupplierCredential existing = stored("old_key_1234", true, SupplierEnvironment.UAT, "https://apitest.tripjack.com");
        String cipher = existing.getApiKeyEncrypted();
        when(repository.findById("C1")).thenReturn(Optional.of(existing));

        PlatformSupplierCredentialResponse response = service.update("C1", updateRequest("  ", null));

        assertThat(existing.getApiKeyEncrypted()).isEqualTo(cipher);
        assertThat(existing.getName()).isEqualTo("Tripjack India");
        assertThat(existing.getIsActive()).isTrue();
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••1234");
        assertThat(response.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        // a rename alone is not refused for bookings in flight
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void aNewKeyKeepsTheStoredEnvironmentAndBaseUrl() {
        PlatformSupplierCredential existing = stored("old_key_1234", true, SupplierEnvironment.UAT, "https://apitest.tripjack.com");
        when(repository.findById("C1")).thenReturn(Optional.of(existing));

        PlatformSupplierCredentialResponse response = service.update("C1", updateRequest("tj_uat_key_7777", true));

        assertThat(existing.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        assertThat(existing.getBaseUrl()).isEqualTo("https://apitest.tripjack.com");
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••7777");
        assertThat(auditDetail(PlatformAuditAction.CREDENTIAL_SET)).containsEntry("keyChanged", true);
    }

    @Test
    void aKeyChangeOrToggleIsBlockedWhileBookingsInFlight() {
        // a cancel in progress is still addressed to the supplier under the stamped credential
        assertThat(PlatformBookingStatus.SUPPLIER_PENDING).contains(PlatformBookingStatus.CANCELLING);
        when(repository.findById("C1")).thenReturn(Optional.of(stored("old_key_1234", true)));
        when(bookingRepository.countByStatusIn(PlatformBookingStatus.SUPPLIER_PENDING)).thenReturn(2L);

        assertThatThrownBy(() -> service.update("C1", updateRequest("tj_new_key_1234", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2 booking");
        assertThatThrownBy(() -> service.update("C1", updateRequest(null, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2 booking");
        verify(repository, never()).save(any());
        verifyNoInteractions(platformAuditService);
    }

    @Test
    void termsChangeIsNeverBlockedByBookingsInFlightAndIsAudited() {
        PlatformSupplierCredential existing = stored("old_key_1234", true, SupplierEnvironment.UAT, "https://apitest.tripjack.com");
        when(repository.findById("C1")).thenReturn(Optional.of(existing));

        service.updateProducts("C1", List.of(terms(PricingProduct.HOTEL, "7.25")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlatformSupplierProduct>> rows = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(p -> {
            assertThat(p.getProduct()).isEqualTo(PricingProduct.HOTEL);
            assertThat(p.getCommissionEstimatePct()).isEqualByComparingTo("7.25");
            assertThat(p.getUpdatedBy()).isEqualTo("SA1");
        });
        assertThat(existing.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        verifyNoInteractions(bookingRepository);
        assertThat(auditDetail(PlatformAuditAction.SUPPLIER_TERMS_SET).toString()).contains("HOTEL");
    }

    @Test
    void commissionEstimateComesFromTheActiveSupplierElseFive() {
        PlatformSupplierCredential active = stored("k_12345", true);
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(active));
        when(productRepository.findByCredentialIdAndProduct("C1", PricingProduct.FLIGHT)).thenReturn(Optional.of(
                PlatformSupplierProduct.builder().credentialId("C1").product(PricingProduct.FLIGHT)
                        .commissionEstimatePct(new BigDecimal("3.5")).build()));
        when(productRepository.findByCredentialIdAndProduct("C1", PricingProduct.HOTEL)).thenReturn(Optional.empty());

        assertThat(service.commissionEstimatePct(PricingProduct.FLIGHT)).isEqualByComparingTo("3.5");
        assertThat(service.commissionEstimatePct(PricingProduct.HOTEL)).isEqualByComparingTo("5");

        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());
        assertThat(service.commissionEstimatePct(PricingProduct.FLIGHT)).isEqualByComparingTo("5");
    }

    @Test
    void resolvedCredentialToStringMasksTheKey() {
        ResolvedCredential c = new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT,
                "https://apitest.tripjack.com", "tj_live_key_9f3a", "voyra-user-0042");

        assertThat(c.toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("voyra-user-0042")
                .contains("••••••9f3a").contains("••••••0042");
    }

    @Test
    void resolveActiveReturnsDecryptedKey() {
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(stored("tj_live_key_9f3a", true)));

        ResolvedCredential credential = service.resolveActive(SupplierProvider.TRIPJACK);

        assertThat(credential.apiKey()).isEqualTo("tj_live_key_9f3a");
        assertThat(credential.environment()).isEqualTo(SupplierEnvironment.PROD);
        assertThat(credential.baseUrl()).isEqualTo("https://api.tripjack.com");
        assertThat(credential.userId()).isNull();
    }

    @Test
    void resolveByIdWorksForAnInactiveRow() {
        when(repository.findById("C1")).thenReturn(Optional.of(stored("tj_off_key_1111", false)));

        assertThat(service.resolve("C1").apiKey()).isEqualTo("tj_off_key_1111");
    }

    /** A row saved before the host guard existed must not keep sending the key to an off-list host. */
    @Test
    void resolveRefusesAStoredHostOffTheAllowlist() {
        PlatformSupplierCredential old = stored("tj_live_key_9f3a", true, SupplierEnvironment.PROD, "https://attacker.example.com");
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
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(stored("tj_live_key_9f3a", true)));
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
}
