package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierCredentialResponse;
import com.voyra.crm.dto.SupplierCredentialRevealResponse;
import com.voyra.crm.dto.SupplierCredentialUpsertRequest;
import com.voyra.crm.entity.SupplierCredential;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.SupplierCredentialRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Encrypt/decrypt round trip, masking, and the two access rules that matter most here: an
 * agency with no active credential must fail loudly rather than let a search fall through to
 * fabricated fares, and only the agency owner may reach the plaintext-reveal path.
 */
@ExtendWith(MockitoExtension.class)
class SupplierCredentialServiceTest {

    /** Same value as application-test.properties - a real 32-byte AES-256 key, test-only. */
    private static final String TEST_KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";

    @Mock
    private SupplierCredentialRepository supplierCredentialRepository;

    @InjectMocks
    private SupplierCredentialService supplierCredentialService;

    @BeforeEach
    void setEncryptionKey() {
        ReflectionTestUtils.setField(supplierCredentialService, "encryptionKey", TEST_KEY);
    }

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private SupplierCredentialUpsertRequest upsertRequest(String apiKey) {
        SupplierCredentialUpsertRequest request = new SupplierCredentialUpsertRequest();
        request.setProvider(SupplierProvider.TRIPJACK);
        request.setEnvironment(SupplierEnvironment.UAT);
        request.setBaseUrl("https://apitest.tripjack.com");
        request.setApiKey(apiKey);
        request.setIsActive(true);
        return request;
    }

    @Test
    void upsert_encryptsTheKeyAndNeverStoresPlaintext() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());
        when(supplierCredentialRepository.existsById(any())).thenReturn(false);

        supplierCredentialService.upsert(upsertRequest("tj_real_key_9999"));

        ArgumentCaptor<SupplierCredential> captor = ArgumentCaptor.forClass(SupplierCredential.class);
        org.mockito.Mockito.verify(supplierCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isNotEqualTo("tj_real_key_9999");
        assertThat(captor.getValue().getApiKeyEncrypted()).startsWith("ENC:");
    }

    @Test
    void reveal_decryptsBackToTheOriginalKey() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());
        when(supplierCredentialRepository.existsById(any())).thenReturn(false);

        ArgumentCaptor<SupplierCredential> captor = ArgumentCaptor.forClass(SupplierCredential.class);
        supplierCredentialService.upsert(upsertRequest("tj_real_key_9999"));
        org.mockito.Mockito.verify(supplierCredentialRepository).save(captor.capture());
        SupplierCredential saved = captor.getValue();

        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(saved));

        SupplierCredentialRevealResponse revealed = supplierCredentialService.reveal(SupplierProvider.TRIPJACK);
        assertThat(revealed.getApiKey()).isEqualTo("tj_real_key_9999");
    }

    @Test
    void get_neverReturnsThePlaintextKey() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());
        when(supplierCredentialRepository.existsById(any())).thenReturn(false);

        ArgumentCaptor<SupplierCredential> captor = ArgumentCaptor.forClass(SupplierCredential.class);
        supplierCredentialService.upsert(upsertRequest("tj_real_key_9999"));
        org.mockito.Mockito.verify(supplierCredentialRepository).save(captor.capture());
        SupplierCredential saved = captor.getValue();

        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(saved));

        SupplierCredentialResponse response = supplierCredentialService.get(SupplierProvider.TRIPJACK);
        assertThat(response.getMaskedApiKey()).doesNotContain("tj_real_key_9999");
        assertThat(response.getMaskedApiKey()).endsWith("9999");
    }

    @Test
    void reveal_agentIsRejectedEvenIfItReachedTheService() {
        authenticateAs("A1", UserType.AGENT);
        assertThatThrownBy(() -> supplierCredentialService.reveal(SupplierProvider.TRIPJACK))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolveActive_throwsAConfigurationErrorRatherThanFallingBackToNothing() {
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void resolveActive_ignoresAnInactiveCredential() {
        SupplierCredential inactive = SupplierCredential.builder()
                .id("C1").provider(SupplierProvider.TRIPJACK).environment(SupplierEnvironment.UAT)
                .baseUrl("https://apitest.tripjack.com")
                .apiKeyEncrypted("ENC:irrelevant")
                .isActive(false)
                .build();
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> supplierCredentialService.resolveActive(SupplierProvider.TRIPJACK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void upsert_withBlankApiKeyOnUpdatePreservesTheStoredKey() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        String alreadyEncrypted = com.voyra.crm.util.PasswordEncryptionUtil.encrypt("already-there", TEST_KEY);
        SupplierCredential existing = SupplierCredential.builder()
                .id("C1").provider(SupplierProvider.TRIPJACK).environment(SupplierEnvironment.UAT)
                .baseUrl("https://apitest.tripjack.com")
                .apiKeyEncrypted(alreadyEncrypted)
                .isActive(true)
                .build();
        when(supplierCredentialRepository.findByProvider(SupplierProvider.TRIPJACK)).thenReturn(Optional.of(existing));

        SupplierCredentialUpsertRequest request = upsertRequest(null);
        supplierCredentialService.upsert(request);

        ArgumentCaptor<SupplierCredential> captor = ArgumentCaptor.forClass(SupplierCredential.class);
        org.mockito.Mockito.verify(supplierCredentialRepository).save(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isEqualTo(alreadyEncrypted);
    }
}
