package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.VisaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-syncs the owner-name snapshots (the owner's id is the tenant id) after a rename. The caller
 * runs in the public schema, so the tenant is switched here and the update opens a fresh
 * REQUIRES_NEW transaction only after the switch (blueprint 3.5); the previous tenant is always restored.
 */
@Service
public class OwnerNameSyncService {

    private final BookingRepository bookingRepository;
    private final VisaRepository visaRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final TransactionTemplate newTransaction;

    public OwnerNameSyncService(BookingRepository bookingRepository, VisaRepository visaRepository,
                                LeadServiceRepository leadServiceRepository, PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.visaRepository = visaRepository;
        this.leadServiceRepository = leadServiceRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void resync(String tenantId, String ownerName) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            newTransaction.executeWithoutResult(status -> {
                bookingRepository.updateAgentNameForAgent(tenantId, ownerName);
                visaRepository.updateAgentNameForAgent(tenantId, ownerName);
                leadServiceRepository.updateAssignedAgentNameForAgent(tenantId, ownerName);
            });
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }
}
