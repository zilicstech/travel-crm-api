package com.voyra.crm.service;

import com.voyra.crm.dto.PublicProposalItemResponse;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.ProposalItem;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.ProposalItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant-scoped work for the unauthenticated public proposal endpoints. Runs in a fresh
 * REQUIRES_NEW transaction acquired AFTER {@link PublicProposalService} sets
 * {@link com.voyra.crm.context.TenantContext} (blueprint §3.5) - must live on its own bean,
 * never called via self-invocation. The DTO is built HERE, inside the correctly-scoped
 * transaction, so a pricing/cost field can never leak by being serialized from a raw entity
 * later, outside this method's control.
 */
@Service
@RequiredArgsConstructor
public class PublicProposalTenantService {

    private static final Set<LeadStatus> ALREADY_PAST_NEGOTIATING =
            Set.of(LeadStatus.NEGOTIATING, LeadStatus.BOOKED, LeadStatus.LOST);

    private final LeadRepository leadRepository;
    private final ProposalItemRepository proposalItemRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PublicProposalResponse> fetchProposal(String leadId) {
        return leadRepository.findById(leadId).map(this::buildResponse);
    }

    /** Idempotent: approving an already-approved-or-further-along proposal is a no-op success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean approve(String leadId) {
        Optional<Lead> leadOpt = leadRepository.findById(leadId);
        if (leadOpt.isEmpty()) {
            return false;
        }
        Lead lead = leadOpt.get();
        if (!ALREADY_PAST_NEGOTIATING.contains(lead.getStatus())) {
            lead.setStatus(LeadStatus.NEGOTIATING);
            leadRepository.save(lead);
        }
        return true;
    }

    private PublicProposalResponse buildResponse(Lead lead) {
        var items = proposalItemRepository.findByLeadId(lead.getId());
        BigDecimal grandTotal = items.stream()
                .map(ProposalItem::getSellingPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int guestCount = safe(lead.getAdults()) + safe(lead.getChildren()) + safe(lead.getInfants());

        return PublicProposalResponse.builder()
                .customerName(lead.getName())
                .destination(lead.getDestination())
                .travelDateFrom(lead.getTravelDateFrom())
                .travelDateTo(lead.getTravelDateTo())
                .guestCount(guestCount)
                .items(items.stream()
                        .map(i -> PublicProposalItemResponse.builder()
                                .type(i.getType()).description(i.getDescription())
                                .supplier(i.getSupplier()).sellingPrice(i.getSellingPrice())
                                .build())
                        .toList())
                .grandTotal(grandTotal)
                .build();
    }

    private int safe(Integer value) {
        return value != null ? value : 0;
    }
}
