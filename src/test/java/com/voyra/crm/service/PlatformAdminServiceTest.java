package com.voyra.crm.service;

import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.dto.PlatformAdminCreateRequest;
import com.voyra.crm.dto.PlatformAdminResponse;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.entity.PlatformSettingRequest;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.PlatformSettingRequestRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform admins: add (password generated once), deactivate, reactivate; at least one stays active; all audited. */
@ExtendWith(MockitoExtension.class)
class PlatformAdminServiceTest {

    @Mock private PlatformAdminRepository repository;
    @Mock private AesPasswordEncoder passwordEncoder;
    @Mock private LoginEmailService loginEmailService;
    @Mock private PlatformAuditService auditService;
    @Mock private PlatformSettingRequestRepository settingRequestRepository;
    @InjectMocks private PlatformAdminService service;

    private final Map<String, PlatformAdmin> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any())).thenAnswer(i -> {
            PlatformAdmin a = i.getArgument(0);
            rows.put(a.getId(), a);
            return a;
        });
        lenient().when(repository.existsById(anyString())).thenAnswer(i -> rows.containsKey(i.<String>getArgument(0)));
        lenient().when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(repository.findActiveForUpdate()).thenAnswer(i -> rows.values().stream().filter(PlatformAdmin::getIsActive).toList());
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("SA1", "root@voyra.test", UserType.SUPER_ADMIN, null), null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void admin(String id, boolean active) {
        rows.put(id, PlatformAdmin.builder().id(id).name("Admin " + id).email(id.toLowerCase() + "@voyra.test")
                .password("enc:x").isActive(active).build());
    }

    private static PlatformAdminCreateRequest request(String name, String email) {
        PlatformAdminCreateRequest r = new PlatformAdminCreateRequest();
        r.setName(name);
        r.setEmail(email);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void addGeneratesAPasswordShownOnceStoresItEncryptedAndAuditsWithoutIt() {
        PlatformAdminResponse created = service.create(request(" Priya Nair ", " priya@voyra.test "));

        assertThat(created.getPassword()).hasSize(16).matches("[A-HJ-NP-Za-km-z2-9]+");
        assertThat(created.getEmail()).isEqualTo("priya@voyra.test");
        assertThat(created.getName()).isEqualTo("Priya Nair");
        assertThat(created.isActive()).isTrue();
        verify(loginEmailService).assertFree("priya@voyra.test");
        PlatformAdmin saved = rows.get(created.getId());
        assertThat(saved.getPassword()).isEqualTo("enc:" + created.getPassword());
        assertThat(PlatformAdminCache.isActive(created.getId())).isTrue();

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PLATFORM_ADMIN_CREATE), eq("PLATFORM_ADMIN"), eq(created.getId()), detail.capture());
        assertThat(detail.getValue()).containsEntry("email", "priya@voyra.test").doesNotContainValue(created.getPassword());
        // the list never carries a password
        when(repository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.copyOf(rows.values()));
        assertThat(service.list()).allSatisfy(a -> assertThat(a.getPassword()).isNull());
    }

    @Test
    void twoCreatesGetDifferentPasswords() {
        String a = service.create(request("A", "a@voyra.test")).getPassword();
        String b = service.create(request("B", "b@voyra.test")).getPassword();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void anEmailUsedByAnotherLoginIsRefused() {
        doThrow(new IllegalArgumentException("This email is already used by another login - use a different email"))
                .when(loginEmailService).assertFree("taken@voyra.test");

        assertThatThrownBy(() -> service.create(request("T", "taken@voyra.test"))).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void theLastActiveAdminCannotBeDeactivated() {
        admin("SA1", true);
        admin("SA2", false);

        assertThatThrownBy(() -> service.deactivate("SA1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only active platform admin");
        assertThat(rows.get("SA1").getIsActive()).isTrue();
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void anAdminMayDeactivateThemselvesWhileAnotherStaysActive() {
        admin("SA1", true);
        admin("SA2", true);
        PlatformAdminCache.put("SA1", true);

        PlatformAdminResponse r = service.deactivate("SA1");

        assertThat(r.isActive()).isFalse();
        assertThat(rows.get("SA1").getIsActive()).isFalse();
        assertThat(PlatformAdminCache.isActive("SA1")).isFalse();
        verify(auditService).record(eq(PlatformAuditAction.PLATFORM_ADMIN_DEACTIVATE), eq("PLATFORM_ADMIN"), eq("SA1"), anyMap());
        // now SA2 is the last one
        assertThatThrownBy(() -> service.deactivate("SA2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deactivatingAnInactiveAdminChangesNothing() {
        admin("SA1", true);
        admin("SA2", false);

        assertThat(service.deactivate("SA2").isActive()).isFalse();
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void reactivateTurnsTheLoginBackOnAndAudits() {
        admin("SA1", true);
        admin("SA2", false);

        assertThat(service.reactivate("SA2").isActive()).isTrue();
        assertThat(rows.get("SA2").getIsActive()).isTrue();
        assertThat(PlatformAdminCache.isActive("SA2")).isTrue();
        verify(auditService).record(eq(PlatformAuditAction.PLATFORM_ADMIN_REACTIVATE), eq("PLATFORM_ADMIN"), eq("SA2"), anyMap());
    }

    @Test
    void unknownAdminIs404() {
        admin("SA1", true);
        assertThatThrownBy(() -> service.deactivate("NOPE")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reactivate("NOPE")).isInstanceOf(NotFoundException.class);
    }

    /** Final review minor 9: a deactivated admin's open setting requests are withdrawn (audited), so nobody can approve them later. */
    @Test
    void deactivatingAnAdminWithdrawsTheirOpenSettingRequests() {
        admin("SA1", true);
        admin("SA2", true);
        PlatformSettingRequest open = PlatformSettingRequest.builder().id("R1").settingKey("tripjack_live_ready").value("true")
                .requestedBy("SA2").requestedAt(Instant.now()).build();
        when(settingRequestRepository.findByRequestedByAndDecidedAtIsNull("SA2")).thenReturn(List.of(open));

        service.deactivate("SA2");

        assertThat(open.getOutcome()).isEqualTo("WITHDRAWN");
        assertThat(open.getDecidedBy()).isEqualTo("SA1");
        assertThat(open.getDecidedAt()).isNotNull();
        verify(settingRequestRepository).save(open);
        verify(auditService).record(eq(PlatformAuditAction.SETTING_CHANGE_WITHDRAW), eq("PLATFORM_SETTING"), eq("tripjack_live_ready"), anyMap());
    }

    /** Carried minor: the liveness cache changes only after the commit; a rolled-back deactivate leaves it as it was. */
    @Test
    void theCacheChangesOnlyWhenTheTransactionCommits() {
        admin("SA1", true);
        admin("SA2", true);
        PlatformAdminCache.put("SA2", true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deactivate("SA2");
            assertThat(PlatformAdminCache.isActive("SA2")).isTrue(); // not yet committed
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(PlatformAdminCache.isActive("SA2")).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            admin("SA3", true);
            PlatformAdminCache.put("SA3", true);
            service.deactivate("SA3");
            assertThat(PlatformAdminCache.isActive("SA3")).isTrue();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(PlatformAdminCache.isActive("SA3")).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
