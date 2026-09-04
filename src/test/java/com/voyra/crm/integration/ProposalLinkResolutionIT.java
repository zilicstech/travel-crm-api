package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.AgencyService;
import com.voyra.crm.service.ProposalLinkService;
import com.voyra.crm.service.PublicProposalService;
import com.voyra.crm.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOT VERIFIED: Docker is unavailable on the machine this was written on, so this class has
 * never actually been run. Written carefully against the real service/repository signatures,
 * but treat it as a draft to run and fix, not a passing test, until executed once with a
 * working Docker daemon (blueprint §3.5).
 *
 * Confirms the unauthenticated public proposal path resolves the correct tenant schema from
 * a bare token with no tenant context and no authenticated principal at all - the token is
 * the only tenant binding, so two tokens from two tenants can never cross-resolve.
 */
class ProposalLinkResolutionIT extends AbstractIntegrationTest {

    @Autowired
    private AgencyService agencyService;
    @Autowired
    private LeadRepository leadRepository;
    @Autowired
    private ProposalLinkService proposalLinkService;
    @Autowired
    private PublicProposalService publicProposalService;

    private String provisionTenant(String agencyName, String ownerEmail) {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName(agencyName);
        request.setOwnerName("Owner " + agencyName);
        request.setOwnerEmail(ownerEmail);
        request.setOwnerPassword("TestPass123!");
        AgencyCreateResponse response = agencyService.createAgency(request);
        return response.getId();
    }

    private String createLeadInTenant(String tenantId, String clientName) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            Lead lead = Lead.builder()
                    .id(IdGenerator.generateId())
                    .clientId(IdGenerator.generateId())
                    .clientName(clientName)
                    .clientType(ClientType.B2C)
                    .destination("Nowhere")
                    .adults(2)
                    .kids(0)
                    .kidAges(List.of())
                    .totalTravellers(2)
                    .status(LeadStatus.NEW)
                    .source("PHONE_CALL")
                    .priority(LeadPriority.MEDIUM)
                    .categories(List.of())
                    .createdBy("A1")
                    .createdByName("Test Agent")
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            return leadRepository.save(lead).getId();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    /** Owner-authenticated in the given tenant, exactly as the real "generate link" endpoint requires. */
    private String generateProposalLinkInTenant(String tenantId, String leadId) {
        String previousTenant = TenantContext.getTenantId();
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            TenantContext.setTenantId(tenantId);
            return proposalLinkService.generateLink(leadId).getToken();
        } finally {
            SecurityContextHolder.clearContext();
            if (previousTenant == null || previousTenant.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previousTenant);
            }
        }
    }

    @Test
    void resolvesTheCorrectLeadWithNoTenantContextAndNoAuthenticationAtAll() {
        String tenantA = provisionTenant("Resolution Agency A", "owner-ra@isolation-test.com");
        String leadIdInA = createLeadInTenant(tenantA, "Jane From Agency A");
        String token = generateProposalLinkInTenant(tenantA, leadIdInA);

        // Simulate a fresh, fully unauthenticated public request thread.
        TenantContext.clear();
        SecurityContextHolder.clearContext();

        PublicProposalResponse response = publicProposalService.getProposal(token);

        assertThat(response.getClientName()).isEqualTo("Jane From Agency A");
    }

    @Test
    void eachTokenResolvesOnlyItsOwnTenantsDataNeverCrossResolving() {
        String tenantA = provisionTenant("Resolution Agency B", "owner-rb@isolation-test.com");
        String tenantB = provisionTenant("Resolution Agency C", "owner-rc@isolation-test.com");

        String leadInA = createLeadInTenant(tenantA, "Lead From Agency B");
        String leadInB = createLeadInTenant(tenantB, "Lead From Agency C");
        String tokenA = generateProposalLinkInTenant(tenantA, leadInA);
        String tokenB = generateProposalLinkInTenant(tenantB, leadInB);

        TenantContext.clear();
        SecurityContextHolder.clearContext();
        assertThat(publicProposalService.getProposal(tokenA).getClientName()).isEqualTo("Lead From Agency B");

        TenantContext.clear();
        SecurityContextHolder.clearContext();
        assertThat(publicProposalService.getProposal(tokenB).getClientName()).isEqualTo("Lead From Agency C");
    }
}
