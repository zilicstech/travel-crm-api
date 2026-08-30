package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.service.AgencyService;
import com.voyra.crm.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Converts PROGRESS.md's manually-verified isolation claim into an executable one: two real
 * provisioned tenant schemas, and guessing a valid ID from the other tenant returns nothing,
 * because search_path never points at the wrong schema.
 *
 * <p>Covers the whole client/member/lead spine rather than one table. The traveller manifest
 * is the case worth being explicit about: {@code lead_members} carries the link between a
 * lead and a person, and a leak there would expose who travels with whom across agencies.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private AgencyService agencyService;
    @Autowired
    private LeadRepository leadRepository;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private LeadMemberRepository leadMemberRepository;

    private String provisionTenant(String agencyName, String ownerEmail) {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName(agencyName);
        request.setOwnerName("Owner " + agencyName);
        request.setOwnerEmail(ownerEmail);
        AgencyCreateResponse response = agencyService.createAgency(request);
        return response.getId();
    }

    /**
     * Runs a unit of work against one tenant and always restores whatever context was active
     * before, so a failing assertion cannot leave the ThreadLocal pointing at a tenant the next
     * test then silently writes into (blueprint §3.5 rule 1).
     */
    private <T> T inTenant(String tenantId, java.util.function.Supplier<T> work) {
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

    private String createClientInTenant(String tenantId) {
        return inTenant(tenantId, () -> {
            Client client = Client.builder()
                    .id(IdGenerator.generateId())
                    .identifier("9999999999")
                    .name("Isolation Test Client")
                    .type(ClientType.B2C)
                    .agentId("A1")
                    .agentName("Test Agent")
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            return clientRepository.save(client).getId();
        });
    }

    private String createMemberInTenant(String tenantId, String clientId) {
        return inTenant(tenantId, () -> {
            Member member = Member.builder()
                    .memberId(IdGenerator.generateId())
                    .clientId(clientId)
                    .name("Isolation Test Member")
                    .type(MemberType.CLIENT)
                    .relation(MemberRelation.SELF)
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            return memberRepository.save(member).getMemberId();
        });
    }

    private String createLeadInTenant(String tenantId, String clientId) {
        return inTenant(tenantId, () -> {
            Lead lead = Lead.builder()
                    .id(IdGenerator.generateId())
                    .clientId(clientId)
                    .clientName("Isolation Test Client")
                    .clientType(ClientType.B2C)
                    .destination("Nowhere")
                    .adults(1)
                    .kids(0)
                    .kidAges(List.of())
                    .totalTravellers(1)
                    .status(LeadStatus.NEW)
                    .source(LeadSource.PHONE_CALL)
                    .priority(LeadPriority.MEDIUM)
                    .categories(List.of())
                    .assignedTo("A1")
                    .assignedAgentName("Test Agent")
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            return leadRepository.save(lead).getId();
        });
    }

    private String createManifestRowInTenant(String tenantId, String leadId, String memberId, String clientId) {
        return inTenant(tenantId, () -> {
            LeadMember row = LeadMember.builder()
                    .id(IdGenerator.generateId())
                    .leadId(leadId)
                    .memberId(memberId)
                    .clientId(clientId)
                    .memberName("Isolation Test Member")
                    .status(LeadMemberStatus.TENTATIVE)
                    .createdAt(LocalDateTime.now())
                    .build();
            return leadMemberRepository.save(row).getId();
        });
    }

    @Test
    void guessingAValidLeadIdFromAnotherTenantReturnsNothing() {
        String tenantA = provisionTenant("Isolation Agency A", "owner-a@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency B", "owner-b@isolation-test.com");

        String clientIdInA = createClientInTenant(tenantA);
        String leadIdInA = createLeadInTenant(tenantA, clientIdInA);

        assertThat(inTenant(tenantB, () -> leadRepository.findById(leadIdInA))).isEmpty();
    }

    @Test
    void guessingAValidClientIdFromAnotherTenantReturnsNothingInEitherDirection() {
        String tenantA = provisionTenant("Isolation Agency C", "owner-c@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency D", "owner-d@isolation-test.com");

        String clientIdInA = createClientInTenant(tenantA);
        String clientIdInB = createClientInTenant(tenantB);

        assertThat(inTenant(tenantB, () -> clientRepository.findById(clientIdInA))).isEmpty();
        assertThat(inTenant(tenantA, () -> clientRepository.findById(clientIdInB))).isEmpty();
    }

    @Test
    void guessingAValidMemberIdFromAnotherTenantReturnsNothingInEitherDirection() {
        String tenantA = provisionTenant("Isolation Agency F", "owner-f@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency G", "owner-g@isolation-test.com");

        String memberIdInA = createMemberInTenant(tenantA, createClientInTenant(tenantA));
        String memberIdInB = createMemberInTenant(tenantB, createClientInTenant(tenantB));

        assertThat(inTenant(tenantB, () -> memberRepository.findById(memberIdInA))).isEmpty();
        assertThat(inTenant(tenantA, () -> memberRepository.findById(memberIdInB))).isEmpty();
    }

    /**
     * The manifest row is the one that says which person travels on which enquiry. A leak here
     * would expose a rival agency's party composition even if every other table held.
     */
    @Test
    void guessingAValidManifestRowIdFromAnotherTenantReturnsNothing() {
        String tenantA = provisionTenant("Isolation Agency H", "owner-h@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency I", "owner-i@isolation-test.com");

        String clientIdInA = createClientInTenant(tenantA);
        String memberIdInA = createMemberInTenant(tenantA, clientIdInA);
        String leadIdInA = createLeadInTenant(tenantA, clientIdInA);
        String rowIdInA = createManifestRowInTenant(tenantA, leadIdInA, memberIdInA, clientIdInA);

        assertThat(inTenant(tenantB, () -> leadMemberRepository.findById(rowIdInA))).isEmpty();
        assertThat(inTenant(tenantB, () -> leadMemberRepository.findByLeadIdOrderByCreatedAtAsc(leadIdInA))).isEmpty();
    }

    @Test
    void tenantContextDoesNotLeakAcrossThreadLocalBoundary() {
        String tenantA = provisionTenant("Isolation Agency E", "owner-e@isolation-test.com");

        TenantContext.setTenantId(tenantA);
        TenantContext.clear();

        assertThat(TenantContext.getTenantId()).isNull();
    }
}
