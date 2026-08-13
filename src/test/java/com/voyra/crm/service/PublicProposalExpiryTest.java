package com.voyra.crm.service;

import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.repository.ProposalLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

/**
 * Unknown and expired tokens must be indistinguishable to the caller - both are the exact
 * same generic failure, so a guessed token reveals nothing about whether it ever existed.
 */
@ExtendWith(MockitoExtension.class)
class PublicProposalExpiryTest {

    @Mock
    private ProposalLinkRepository proposalLinkRepository;
    @Mock
    private PublicProposalTenantService tenantService;

    @InjectMocks
    private PublicProposalService publicProposalService;

    @Test
    void unknownTokenThrows() {
        when(proposalLinkRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicProposalService.getProposal("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredTokenThrows() {
        ProposalLink expired = ProposalLink.builder()
                .token("EXPIRED").tenantId("T1").leadId("L1")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(proposalLinkRepository.findById("EXPIRED")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> publicProposalService.getProposal("EXPIRED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAndExpiredProduceTheIdenticalMessage() {
        when(proposalLinkRepository.findById("UNKNOWN")).thenReturn(Optional.empty());
        ProposalLink expired = ProposalLink.builder()
                .token("EXPIRED").tenantId("T1").leadId("L1")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(proposalLinkRepository.findById("EXPIRED")).thenReturn(Optional.of(expired));

        Throwable unknownError = catchThrowable(() -> publicProposalService.getProposal("UNKNOWN"));
        Throwable expiredError = catchThrowable(() -> publicProposalService.getProposal("EXPIRED"));

        assertThat(unknownError.getMessage()).isEqualTo(expiredError.getMessage());
    }
}
