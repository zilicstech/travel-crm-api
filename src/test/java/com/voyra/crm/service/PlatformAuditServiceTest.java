package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.PlatformAuditLogRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class PlatformAuditServiceTest {

    @Mock
    private PlatformAuditLogRepository repository;

    @InjectMocks
    private PlatformAuditService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "root", role, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void recordsTheSuperAdminAsActorWithoutResolvingATenant() {
        authenticateAs("root-1", UserType.SUPER_ADMIN);
        when(repository.existsById(anyString())).thenReturn(false);

        service.record(PlatformAuditAction.PRICING_CREATE, "AGENCY_PRICING", "p1", Map.of("discountPct", 10));

        ArgumentCaptor<PlatformAuditLog> c = ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(repository).save(c.capture());
        assertEquals("root-1", c.getValue().getActorId());
        assertEquals(PlatformAuditAction.PRICING_CREATE, c.getValue().getAction());
        assertEquals(10, c.getValue().getDetail().get("discountPct"));
    }
}
