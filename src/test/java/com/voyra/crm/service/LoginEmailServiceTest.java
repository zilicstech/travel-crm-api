package com.voyra.crm.service;

import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** CRD-01: one email, one login - across owners, staff and platform admins. */
@ExtendWith(MockitoExtension.class)
class LoginEmailServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private PlatformAdminRepository platformAdminRepository;

    @InjectMocks
    private LoginEmailService loginEmailService;

    @Test
    void anOwnersEmailCannotBeReusedForStaff() {
        when(tenantRepository.existsByOwnerEmailIgnoreCase("Dana.Owner@dummyqa.com")).thenReturn(true);

        assertThatThrownBy(() -> loginEmailService.assertFree("Dana.Owner@dummyqa.com"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already used");
    }

    @Test
    void aStaffEmailCannotBeReusedForAnOwner() {
        when(agentRepository.existsByEmailIgnoreCase("vikram@x.com")).thenReturn(true);

        assertThatThrownBy(() -> loginEmailService.assertFree("vikram@x.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlatformAdminEmailCannotBeReused() {
        when(platformAdminRepository.existsByEmailIgnoreCase("admin@travelos.com")).thenReturn(true);

        assertThatThrownBy(() -> loginEmailService.assertFree("admin@travelos.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFreshEmailPasses() {
        assertThatCode(() -> loginEmailService.assertFree("new@x.com")).doesNotThrowAnyException();
    }
}
