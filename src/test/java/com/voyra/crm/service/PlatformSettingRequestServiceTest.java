package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSettingRequestResponse;
import com.voyra.crm.entity.PlatformSettingRequest;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformSettingRequestRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Review Focus 3: tripjack_live_ready goes on only when a second platform admin approves another admin's request. */
@ExtendWith(MockitoExtension.class)
class PlatformSettingRequestServiceTest {

    @Mock private PlatformSettingRequestRepository repository;
    @Mock private PlatformSettingService settings;
    @Mock private PlatformAuditService auditService;
    @InjectMocks private PlatformSettingRequestService service;

    private final Map<String, PlatformSettingRequest> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any())).thenAnswer(i -> {
            PlatformSettingRequest r = i.getArgument(0);
            rows.put(r.getId(), r);
            return r;
        });
        lenient().when(repository.existsById(anyString())).thenAnswer(i -> rows.containsKey(i.<String>getArgument(0)));
        lenient().when(repository.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        lenient().when(repository.findFirstBySettingKeyAndDecidedAtIsNull(anyString())).thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getSettingKey().equals(i.getArgument(0)) && r.getDecidedAt() == null).findFirst());
        loginAs("SA1");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, userId + "@voyra.test", UserType.SUPER_ADMIN, null), null));
    }

    @Test
    void anotherAdminApprovesAndTheSettingIsWrittenAndAudited() {
        PlatformSettingRequestResponse req = service.request("tripjack_live_ready", "true");
        assertThat(req.getRequestedBy()).isEqualTo("SA1");
        assertThat(req.getStatus()).isEqualTo("PENDING");
        verify(auditService).record(eq(PlatformAuditAction.SETTING_CHANGE_REQUEST), eq("PLATFORM_SETTING"), eq("tripjack_live_ready"), any());
        verify(settings, never()).applyApproved(anyString(), anyString());

        loginAs("SA2");
        PlatformSettingRequestResponse done = service.approve(req.getId());

        assertThat(done.getStatus()).isEqualTo("APPROVED");
        assertThat(done.getDecidedBy()).isEqualTo("SA2");
        verify(settings).applyApproved("tripjack_live_ready", "true");
        verify(auditService).record(eq(PlatformAuditAction.SETTING_CHANGE_APPROVE), eq("PLATFORM_SETTING"), eq("tripjack_live_ready"), any());
    }

    @Test
    void theRequesterCannotApproveTheirOwnRequest() {
        PlatformSettingRequestResponse req = service.request("tripjack_live_ready", "true");

        assertThatThrownBy(() -> service.approve(req.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("A different platform admin must approve this change");
        verify(settings, never()).applyApproved(anyString(), anyString());
    }

    @Test
    void oneOpenRequestAtATimeAndOnlyTheLiveSwitchCanBeRequested() {
        service.request("tripjack_live_ready", "true");
        assertThatThrownBy(() -> service.request("tripjack_live_ready", "true")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.request("tds_rate", "5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request("tripjack_live_ready", "false")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWithdrawnOrApprovedRequestCannotBeDecidedAgain() {
        PlatformSettingRequestResponse req = service.request("tripjack_live_ready", "true");
        PlatformSettingRequestResponse withdrawn = service.withdraw(req.getId());
        assertThat(withdrawn.getStatus()).isEqualTo("WITHDRAWN");
        verify(auditService).record(eq(PlatformAuditAction.SETTING_CHANGE_WITHDRAW), eq("PLATFORM_SETTING"), eq("tripjack_live_ready"), any());

        loginAs("SA2");
        assertThatThrownBy(() -> service.approve(req.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.withdraw(req.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.approve("nope")).isInstanceOf(NotFoundException.class);
        verify(settings, never()).applyApproved(anyString(), anyString());
        // a fresh request can follow a withdrawn one
        assertThat(service.request("tripjack_live_ready", "true").getRequestedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
