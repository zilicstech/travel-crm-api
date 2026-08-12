package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.repository.ProposalLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Unauthenticated entry point for the public proposal page/actions. No JWT, no tenant claim -
 * the tenant is resolved purely from the opaque token via the public-schema proposal_link
 * index, then the actual read/write happens through {@link PublicProposalTenantService} in a
 * fresh transaction after the tenant context is set (blueprint §3.5's cross-tenant pattern).
 *
 * Both public methods throw a single generic {@link IllegalArgumentException} for every
 * "not found" case (unknown token, expired token, or a token whose lead vanished) - the
 * caller must never be able to tell which one happened, so the same message covers all three.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicProposalService {

    private static final String NOT_FOUND_MESSAGE = "This proposal link is invalid or has expired";

    private final ProposalLinkRepository proposalLinkRepository;
    private final PublicProposalTenantService tenantService;

    @Transactional(readOnly = true)
    public PublicProposalResponse getProposal(String token) {
        ProposalLink link = resolveLink(token);
        return withTenant(link.getTenantId(), () -> tenantService.fetchProposal(link.getLeadId()))
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
    }

    @Transactional(readOnly = true)
    public void approveProposal(String token) {
        ProposalLink link = resolveLink(token);
        boolean approved = withTenant(link.getTenantId(), () -> tenantService.approve(link.getLeadId()));
        if (!approved) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    private ProposalLink resolveLink(String token) {
        return proposalLinkRepository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
    }

    /** try {set} finally {restore} - never leave a mutated ThreadLocal behind on a pooled request thread. */
    private <T> T withTenant(String tenantId, java.util.function.Supplier<T> work) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            return work.get();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }
}
