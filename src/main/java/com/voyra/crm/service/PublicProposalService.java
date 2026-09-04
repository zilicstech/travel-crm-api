package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.repository.ProposalLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * Not readOnly: the tenant-scoped work this delegates to is a write. The outer
     * transaction only resolves the public-schema token.
     */
    @Transactional
    public void approveProposal(String token) {
        ProposalLink link = resolveLink(token);
        boolean approved = withTenant(link.getTenantId(), () -> tenantService.approve(link.getLeadId()));
        if (!approved) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    /**
     * Not readOnly, same reasoning as {@link #approveProposal}: the write happens in the
     * tenant-scoped delegate, this only resolves the token.
     */
    @Transactional
    public void selectOptions(String token, List<String> selectedItemIds) {
        ProposalLink link = resolveLink(token);
        boolean applied = withTenant(link.getTenantId(), () -> tenantService.selectOptions(link.getLeadId(), selectedItemIds));
        if (!applied) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    /**
     * Resolves the opaque token, treating unknown and expired identically. The caller must
     * never be able to distinguish "no such token" from "token expired" - both are the same
     * generic failure, so a guessed token reveals nothing about whether it ever existed.
     */
    private ProposalLink resolveLink(String token) {
        ProposalLink link = proposalLinkRepository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
        return link;
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
