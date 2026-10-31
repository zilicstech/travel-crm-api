package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.ProposalLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicProposalServiceTest {

    @Mock private ProposalLinkRepository proposalLinkRepository;
    @Mock private PublicProposalTenantService tenantService;
    @Mock private EntitlementService entitlementService;
    @InjectMocks private PublicProposalService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void link(LocalDateTime expiresAt) {
        when(proposalLinkRepository.findById("tok")).thenReturn(Optional.of(
                ProposalLink.builder().token("tok").tenantId("T1").leadId("L1").expiresAt(expiresAt).build()));
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(true);
    }

    @Test
    void aLiveLinkReadsTheProposalInItsAgencyAndRestoresTheCallersTenant() {
        link(LocalDateTime.now().plusDays(1));
        PublicProposalResponse response = PublicProposalResponse.builder().clientName("Asha").build();
        when(tenantService.fetchProposal("L1")).thenAnswer(i -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("T1");
            return Optional.of(response);
        });
        TenantContext.setTenantId("T0");

        assertThat(service.getProposal("tok")).isSameAs(response);
        assertThat(TenantContext.getTenantId()).isEqualTo("T0");
    }

    @Test
    void aLinkWhoseLeadVanishedIsTheSameNotFound() {
        link(null);
        when(tenantService.fetchProposal("L1")).thenReturn(Optional.empty());
        TenantContext.setTenantId(" ");

        assertThatThrownBy(() -> service.getProposal("tok"))
                .isInstanceOf(NotFoundException.class).hasMessage("This proposal link is invalid or has expired");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void approvingAndSelectingGoThroughTheTenantService() {
        link(null);
        when(tenantService.approve("L1")).thenReturn(true, false);
        when(tenantService.selectOptions("L1", List.of("P1"))).thenReturn(true, false);

        service.approveProposal("tok");
        assertThatThrownBy(() -> service.approveProposal("tok"))
                .isInstanceOf(NotFoundException.class).hasMessage("This proposal link is invalid or has expired");
        service.selectOptions("tok", List.of("P1"));
        assertThatThrownBy(() -> service.selectOptions("tok", List.of("P1")))
                .isInstanceOf(NotFoundException.class).hasMessage("This proposal link is invalid or has expired");
        verify(tenantService, org.mockito.Mockito.times(2)).approve("L1");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void unknownExpiredAndUnavailableLinksRevealNothing() {
        assertThatThrownBy(() -> service.getProposal("nope"))
                .isInstanceOf(NotFoundException.class).hasMessage("This proposal link is invalid or has expired");
        when(proposalLinkRepository.findById("old")).thenReturn(Optional.of(
                ProposalLink.builder().token("old").tenantId("T1").leadId("L1").expiresAt(LocalDateTime.now().minusMinutes(1)).build()));
        assertThatThrownBy(() -> service.approveProposal("old"))
                .isInstanceOf(NotFoundException.class).hasMessage("This proposal link is invalid or has expired");
        when(proposalLinkRepository.findById("off")).thenReturn(Optional.of(
                ProposalLink.builder().token("off").tenantId("T2").leadId("L1").build()));
        assertThatThrownBy(() -> service.selectOptions("off", List.of()))
                .isInstanceOf(NotFoundException.class).hasMessage("This link is no longer available");
        verifyNoInteractions(tenantService);
    }
}
