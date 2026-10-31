package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.VisaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerNameSyncServiceTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final VisaRepository visas = mock(VisaRepository.class);
    private final LeadServiceRepository services = mock(LeadServiceRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final OwnerNameSyncService sync = new OwnerNameSyncService(bookings, visas, services, tx);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void resyncsBookingVisaAndServiceNames() {
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TenantContext.setTenantId("PUBLIC-CALLER");

        sync.resync("T1", "New Owner");

        verify(bookings).updateAgentNameForAgent("T1", "New Owner");
        verify(visas).updateAgentNameForAgent("T1", "New Owner");
        verify(services).updateAssignedAgentNameForAgent("T1", "New Owner");
        assertThat(TenantContext.getTenantId()).isEqualTo("PUBLIC-CALLER");
    }

    @Test
    void clearsTheTenantWhenThereWasNoneAndEvenWhenTheUpdateFails() {
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doThrow(new IllegalStateException("boom")).when(bookings).updateAgentNameForAgent("T1", "X");

        assertThatThrownBy(() -> sync.resync("T1", "X")).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.getTenantId()).isNull();
    }
}
