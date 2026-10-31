package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.PlatformAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformAuditServiceSystemTest {

    @Mock private PlatformAuditLogRepository repository;
    @InjectMocks private PlatformAuditService service;

    @Test
    void aScheduledJobIsRecordedAsTheSystemWithNoSignedInUser() {
        service.recordSystem(PlatformAuditAction.WALLET_ADJUST, "WALLET", "W1", Map.of("reason", "nightly"));

        ArgumentCaptor<PlatformAuditLog> saved = ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(repository).save(saved.capture());
        PlatformAuditLog row = saved.getValue();
        assertThat(row.getId()).isNotBlank();
        assertThat(row.getActorId()).isEqualTo("SYSTEM");
        assertThat(row.getActorName()).isEqualTo("System");
        assertThat(row.getAction()).isEqualTo(PlatformAuditAction.WALLET_ADJUST);
        assertThat(row.getEntityType()).isEqualTo("WALLET");
        assertThat(row.getEntityId()).isEqualTo("W1");
        assertThat(row.getDetail()).containsEntry("reason", "nightly");
    }
}
