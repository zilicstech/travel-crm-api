package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSupplierCredentialRequest;
import com.voyra.crm.dto.PlatformSupplierCredentialResponse;
import com.voyra.crm.entity.PlatformSupplierCredential;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformSupplierCredentialRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.util.PasswordEncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The platform's one Tripjack key: stored encrypted, shown masked, never revealed, resolved only for the providers. */
@ExtendWith(MockitoExtension.class)
class PlatformSupplierCredentialServiceTest {

    /** Same value as application-test.properties - a real 32-byte AES-256 key, test-only. */
    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock private PlatformSupplierCredentialRepository repository;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private PlatformBookingRepository bookingRepository;
    @Mock private PlatformSettingService settings;

    @InjectMocks private PlatformSupplierCredentialService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "encryptionKey", KEY);
        org.mockito.Mockito.lenient().when(settings.tripjackLiveReady()).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private PlatformSupplierCredentialRequest request(String apiKey) {
        PlatformSupplierCredentialRequest r = new PlatformSupplierCredentialRequest();
        r.setProvider(SupplierProvider.TRIPJACK);
        r.setEnvironment(SupplierEnvironment.UAT);
        r.setBaseUrl("https://apitest.tripjack.com");
        r.setApiKey(apiKey);
        r.setIsActive(true);
        return r;
    }

    private PlatformSupplierCredential stored(String plainKey, boolean active) {
        return PlatformSupplierCredential.builder()
                .id("C1").provider(SupplierProvider.TRIPJACK).environment(SupplierEnvironment.PROD)
                .baseUrl("https://api.tripjack.com")
                .apiKeyEncrypted(PasswordEncryptionUtil.encrypt(plainKey, KEY))
                .isActive(active).build();
    }

    @Test
    void upsertEncryptsTheKeyAndMasksIt() {
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of());

        PlatformSupplierCredentialResponse response = service.upsert(request("tj_live_key_9f3a"));

        ArgumentCaptor<PlatformSupplierCredential> saved = ArgumentCaptor.forClass(PlatformSupplierCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKeyEncrypted()).isNotEqualTo("tj_live_key_9f3a");
        assertThat(PasswordEncryptionUtil.decrypt(saved.getValue().getApiKeyEncrypted(), KEY)).isEqualTo("tj_live_key_9f3a");
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••9f3a");
        assertThat(response.getUpdatedBy()).isEqualTo("SA1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(platformAuditService).record(eq(PlatformAuditAction.CREDENTIAL_SET), eq("SUPPLIER_CREDENTIAL"),
                eq("TRIPJACK"), detail.capture());
        assertThat(detail.getValue().toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("9f3a");
    }

    private PlatformSupplierCredentialRequest prodRequest(String apiKey) {
        PlatformSupplierCredentialRequest r = request(apiKey);
        r.setEnvironment(SupplierEnvironment.PROD);
        r.setBaseUrl("https://api.tripjack.com");
        return r;
    }

    @Test
    void upsertWithBlankKeyKeepsTheStoredOne() {
        PlatformSupplierCredential existing = stored("old_key_1234", true);
        String storedCipher = existing.getApiKeyEncrypted();
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of(existing));

        PlatformSupplierCredentialResponse response = service.upsert(prodRequest("  "));

        assertThat(existing.getApiKeyEncrypted()).isEqualTo(storedCipher);
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••1234");
    }

    @Test
    void changingTheEnvironmentWithABlankKeyIsRejected() {
        PlatformSupplierCredential existing = stored("old_key_1234", true);
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.upsert(request("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enter the API key again to change the environment or base URL");
        assertThat(existing.getEnvironment()).isEqualTo(SupplierEnvironment.PROD);
        verify(repository, never()).save(any());
    }

    @Test
    void changingTheBaseUrlWithABlankKeyIsRejected() {
        PlatformSupplierCredential existing = stored("old_key_1234", true);
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of(existing));
        PlatformSupplierCredentialRequest r = prodRequest(null);
        r.setBaseUrl("https://hms-search.tripjack.com");

        assertThatThrownBy(() -> service.upsert(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enter the API key again to change the environment or base URL");
    }

    @Test
    void aHostOffTheAllowlistIsRejected() {
        PlatformSupplierCredentialRequest r = request("tj_new_key_0001");
        r.setBaseUrl("https://attacker.example.com");

        assertThatThrownBy(() -> service.upsert(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a Tripjack UAT host over https");
        verify(repository, never()).save(any());
    }

    @Test
    void aProdHostUnderUatIsRejected() {
        PlatformSupplierCredentialRequest r = request("tj_new_key_0001");
        r.setBaseUrl("https://api.tripjack.com");

        assertThatThrownBy(() -> service.upsert(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a Tripjack UAT host over https");
    }

    /** The providers append their own API paths, and a port or path would let the key reach another service on the host. */
    @Test
    void anAllowedHostWithAPathOrPortIsRejected() {
        for (String url : List.of("https://apitest.tripjack.com/fms/v1", "https://apitest.tripjack.com:8443", "https://apitest.tripjack.com?x=1")) {
            PlatformSupplierCredentialRequest r = request("tj_new_key_0001");
            r.setBaseUrl(url);
            assertThatThrownBy(() -> service.upsert(r))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Base URL must be a Tripjack UAT host over https");
        }
        verify(repository, never()).save(any());
    }

    @Test
    void plainHttpIsRejected() {
        PlatformSupplierCredentialRequest r = prodRequest("tj_new_key_0001");
        r.setBaseUrl("http://api.tripjack.com");

        assertThatThrownBy(() -> service.upsert(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base URL must be a Tripjack PROD host over https");
    }

    @Test
    void anAllowedHostWithANewKeySwitchesEnvironment() {
        PlatformSupplierCredential existing = stored("old_key_1234", true);
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of(existing));
        PlatformSupplierCredentialRequest r = request("tj_uat_key_7777");
        r.setBaseUrl("https://apitest-hms.tripjack.com");

        PlatformSupplierCredentialResponse response = service.upsert(r);

        verify(repository).save(existing);
        assertThat(existing.getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
        assertThat(existing.getBaseUrl()).isEqualTo("https://apitest-hms.tripjack.com");
        assertThat(response.getMaskedApiKey()).isEqualTo("••••••7777");
    }

    @Test
    void resolvedCredentialToStringMasksTheKey() {
        ResolvedCredential c = new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.UAT,
                "https://apitest.tripjack.com", "tj_live_key_9f3a", "voyra-user-0042");

        assertThat(c.toString()).doesNotContain("tj_live_key_9f3a").doesNotContain("voyra-user-0042")
                .contains("••••••9f3a").contains("••••••0042");
    }

    @Test
    void upsertWithoutAKeyOnFirstSaveIsRejected() {
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of());

        assertThatThrownBy(() -> service.upsert(request(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("API key is required");
    }

    @Test
    void activatingDeactivatesTheOtherActiveRowFirst() {
        PlatformSupplierCredential newest = stored("new_key_5678", false);
        PlatformSupplierCredential older = stored("old_key_1234", true).toBuilder().id("C0").build();
        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of(newest, older));
        List<Boolean> targetActiveAtFlush = new ArrayList<>();
        when(repository.saveAllAndFlush(any())).thenAnswer(inv -> {
            targetActiveAtFlush.add(newest.getIsActive());
            return inv.getArgument(0);
        });

        service.upsert(prodRequest(""));

        assertThat(older.getIsActive()).isFalse();
        assertThat(newest.getIsActive()).isTrue();
        InOrder order = inOrder(repository);
        order.verify(repository).saveAllAndFlush(List.of(older));
        order.verify(repository).save(newest);
        // The managed target row must not already be active when the others are flushed, or both reach one flush.
        assertThat(targetActiveAtFlush).containsExactly(false);
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

    /** A row saved before the host guard existed must not keep sending the key to an off-list host. */
    @Test
    void resolveActiveRefusesAStoredHostOffTheAllowlist() {
        PlatformSupplierCredential old = stored("tj_live_key_9f3a", true);
        old.setBaseUrl("https://attacker.example.com");
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.resolveActive(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveActiveWithoutARowThrows() {
        when(repository.findByProviderAndIsActiveTrue(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveActive(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tripjack is not configured on the platform");
    }

    @Test
    void credentialSwitchBlockedWhileBookingsInFlight() {
        // a cancel in progress is still addressed to the supplier under the stamped credential
        assertThat(PlatformBookingStatus.SUPPLIER_PENDING).contains(PlatformBookingStatus.CANCELLING);
        when(bookingRepository.countByStatusIn(PlatformBookingStatus.SUPPLIER_PENDING)).thenReturn(2L);

        assertThatThrownBy(() -> service.upsert(request("tj_new_key_1234")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 booking");
        verify(repository, never()).save(any());
        verifyNoInteractions(platformAuditService);
    }

    @Test
    void thereIsNoRevealMethod() {
        assertThat(Arrays.stream(PlatformSupplierCredentialService.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .doesNotContain("reveal");
    }

    /** Review Focus 3: a PROD key is refused (409) until a second platform admin has turned tripjack_live_ready on; UAT stays open. */
    @Test
    void aProdCredentialIsRefusedUntilTripjackIsLiveReady() {
        when(settings.tripjackLiveReady()).thenReturn(false);

        assertThatThrownBy(() -> service.upsert(prodRequest("tj_live_key_9f3a")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tripjack_live_ready");
        verify(repository, never()).save(any());
        verify(platformAuditService, never()).record(any(), any(), any(), any());

        when(repository.findByProviderOrderByUpdatedAtDesc(SupplierProvider.TRIPJACK)).thenReturn(List.of());
        assertThat(service.upsert(request("tj_uat_key_1234")).getEnvironment()).isEqualTo(SupplierEnvironment.UAT);
    }
}
